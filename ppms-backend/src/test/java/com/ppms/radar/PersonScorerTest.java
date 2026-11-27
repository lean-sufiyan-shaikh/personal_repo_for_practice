package com.ppms.radar;

import com.ppms.radar.LeakageRadarResponse.PersonScore;
import com.ppms.radar.LeakageRadarResponse.ShortageTrend;
import com.ppms.radar.PersonScorer.Person;
import com.ppms.radar.PersonScorer.ShortageTally;
import com.ppms.shift.DiscrepancyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PersonScorerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final RadarPeriod THIRTY_DAYS = RadarPeriod.endingOn(TODAY, 30);
    private static final Person OPERATOR = new Person(7L, "Ravi", "OPERATOR");

    @Test
    void operatorShortFarMoreOftenThanTheRestOfThePumpScoresOnRateAmountAndTrend() {
        List<ShiftOutcomeRow> own = shifts(10, 6, "300");
        ShortageTally rest = new ShortageTally(20, 2, new BigDecimal("200"));

        PersonScore score = PersonScorer.score(OPERATOR, own, rest, List.of(), THIRTY_DAYS);

        // rate 60% vs 10% -> 50 points above x 0.6 = 30; ₹180 vs ₹10 per shift -> 170 / 25 = 7; rising -> 10
        assertThat(reasonPoints(score, "SHORTAGE_RATE")).isEqualTo(30);
        assertThat(reasonPoints(score, "SHORTAGE_AMOUNT")).isEqualTo(7);
        assertThat(reasonPoints(score, "SHORTAGE_TREND")).isEqualTo(10);
        assertThat(score.trend()).isEqualTo(ShortageTrend.RISING);
        assertThat(score.score()).isEqualTo(47);
        assertThat(score.band()).isEqualTo(RadarBand.WATCH);
        assertThat(score.shortShiftCount()).isEqualTo(6);
        assertThat(score.shortAmount()).isEqualByComparingTo("1800");
        assertThat(score.evidence()).hasSize(6).allMatch(evidence -> evidence.kind() == EvidenceKind.SHORT_SHIFT);
    }

    @Test
    void operatorShortNoMoreOftenThanTheRestOfThePumpScoresNothing() {
        PersonScore score = PersonScorer.score(OPERATOR, shifts(10, 1, "300"),
                new ShortageTally(20, 4, new BigDecimal("1200")), List.of(), THIRTY_DAYS);

        assertThat(score.score()).isZero();
        assertThat(score.band()).isEqualTo(RadarBand.LOW);
    }

    @Test
    void shortagesOfTwentyRupeesOrLessAreRoundingAndNotCounted() {
        PersonScore score = PersonScorer.score(OPERATOR, shifts(10, 8, "20"),
                new ShortageTally(20, 0, BigDecimal.ZERO), List.of(), THIRTY_DAYS);

        assertThat(score.shortShiftCount()).isZero();
        assertThat(score.score()).isZero();
    }

    @Test
    void fewerThanFiveShiftsIsNotEnoughDataUnlessCorrectionsScore() {
        PersonScore newcomer = PersonScorer.score(OPERATOR, shifts(4, 4, "500"),
                new ShortageTally(20, 0, BigDecimal.ZERO), List.of(), THIRTY_DAYS);

        assertThat(newcomer.band()).isEqualTo(RadarBand.NOT_ENOUGH_DATA);
        assertThat(newcomer.score()).isZero();
        assertThat(newcomer.reasons()).extracting(ScoreReason::code).contains("NOT_ENOUGH_SHIFTS")
                .doesNotContain("SHORTAGE_RATE");

        PersonScore withVoids = PersonScorer.score(OPERATOR, shifts(4, 0, "0"),
                new ShortageTally(20, 0, BigDecimal.ZERO), corrections(6), THIRTY_DAYS);
        assertThat(withVoids.band()).isEqualTo(RadarBand.WATCH);
        assertThat(withVoids.score()).isEqualTo(40);
    }

    @Test
    void oneCorrectionPerThirtyDaysIsExpectedAndEachFurtherOneAddsEightPointsUpToForty() {
        Person manager = new Person(8L, "Meena", "MANAGER");
        ShortageTally none = new ShortageTally(0, 0, BigDecimal.ZERO);

        assertThat(PersonScorer.score(manager, List.of(), none, corrections(1), THIRTY_DAYS).score()).isZero();
        assertThat(PersonScorer.score(manager, List.of(), none, corrections(4), THIRTY_DAYS).score()).isEqualTo(24);
        assertThat(PersonScorer.score(manager, List.of(), none, corrections(12), THIRTY_DAYS).score()).isEqualTo(40);
        assertThat(PersonScorer.score(manager, List.of(), none, corrections(4), RadarPeriod.endingOn(TODAY, 90)).score())
                .isEqualTo(8);
        assertThat(PersonScorer.score(manager, List.of(), none, corrections(4), THIRTY_DAYS).band())
                .isEqualTo(RadarBand.LOW);
    }

    @Test
    void trendIsFallingWhenTheEarlierHalfHadMoreShortages() {
        List<ShiftOutcomeRow> own = List.of(
                shift(1, TODAY.minusDays(25), "100"), shift(2, TODAY.minusDays(24), "100"), shift(3, TODAY.minusDays(2), "100"));

        assertThat(PersonScorer.trendOf(own, THIRTY_DAYS)).isEqualTo(ShortageTrend.FALLING);
    }

    @Test
    void evidenceIsNewestFirstAndCapped() {
        PersonScore score = PersonScorer.score(OPERATOR, List.of(), new ShortageTally(0, 0, BigDecimal.ZERO),
                corrections(30), THIRTY_DAYS);

        assertThat(score.evidence()).hasSize(PersonScorer.MAX_EVIDENCE);
        assertThat(score.evidence().get(0).date()).isAfterOrEqualTo(score.evidence().get(1).date());
    }

    /** {@code count} shifts in the period; the last {@code shortCount} of them, all in the recent half, are short. */
    private static List<ShiftOutcomeRow> shifts(int count, int shortCount, String shortAmount) {
        List<ShiftOutcomeRow> shifts = new ArrayList<>();
        IntStream.range(0, count).forEach(i -> {
            LocalDate day = TODAY.minusDays(count - 1L - i);
            boolean isShort = i >= count - shortCount;
            shifts.add(isShort ? shift(i, day, shortAmount)
                    : new ShiftOutcomeRow((long) i, OPERATOR.userId(), day, null, BigDecimal.ZERO));
        });
        return shifts;
    }

    private static ShiftOutcomeRow shift(long id, LocalDate day, String shortAmount) {
        return new ShiftOutcomeRow(id, OPERATOR.userId(), day, DiscrepancyType.SHORT, new BigDecimal(shortAmount));
    }

    private static List<RadarEvidence> corrections(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> RadarEvidence.money(EvidenceKind.CREDIT_VOID, TODAY.minusDays(i % 30),
                        new BigDecimal("500"), 1L, (long) i, "Wrong client"))
                .toList();
    }

    private static int reasonPoints(PersonScore score, String code) {
        return score.reasons().stream().filter(reason -> reason.code().equals(code)).findFirst().orElseThrow().points();
    }
}
