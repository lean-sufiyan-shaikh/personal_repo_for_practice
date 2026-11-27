package com.ppms.radar;

import com.ppms.adjustment.NozzleReadingAdjustment;
import com.ppms.radar.LeakageRadarResponse.NozzleScore;
import com.ppms.radar.NozzleScorer.NozzleInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NozzleScorerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final OffsetDateTime FIRST_SHIFT_START = OffsetDateTime.of(2026, 11, 1, 6, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    private static final NozzleInfo NOZZLE = new NozzleInfo(5L, 1, "DU-1", "PETROL", new BigDecimal("99999.999"));

    @Test
    void steadyShiftsThatFollowOnFromEachOtherScoreNothing() {
        NozzleScore score = NozzleScorer.score(NOZZLE, steadyShifts(8, "1000", "500"), List.of(), ZONE);

        assertThat(score.score()).isZero();
        assertThat(score.band()).isEqualTo(RadarBand.LOW);
        assertThat(score.shiftCount()).isEqualTo(8);
    }

    @Test
    void aShiftOpeningBelowThePreviousCloseIsAJumpButAMeterRollingOverIsNot() {
        List<NozzleShiftRow> backwards = new ArrayList<>(steadyShifts(6, "1000", "500"));
        backwards.add(shift(6, "3000", "3500", "500"));

        NozzleScore score = NozzleScorer.score(NOZZLE, backwards, List.of(), ZONE);

        assertThat(score.readingJumpCount()).isEqualTo(1);
        assertThat(score.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.kind()).isEqualTo(EvidenceKind.READING_JUMP);
            assertThat(evidence.fromReading()).isEqualByComparingTo("4000");
            assertThat(evidence.toReading()).isEqualByComparingTo("3000");
        });
        assertThat(score.score()).isEqualTo(NozzleScorer.JUMP_POINTS);

        List<NozzleShiftRow> rollover = List.of(shift(0, "99500", "99999.5", "499.5"), shift(1, "0.5", "500", "499.5"));
        assertThat(NozzleScorer.readingJumps(rollover, List.of(), NOZZLE.maxMeterValue())).isEmpty();
    }

    @Test
    void anAmendmentBetweenTwoShiftsExplainsTheGapAndIsScoredAsAnAmendment() {
        List<NozzleShiftRow> shifts = List.of(shift(0, "1000", "1500", "500"), shift(1, "1200", "1700", "500"));
        NozzleReadingAdjustment lowering = amendment("CUSTOM_READING", "1500", "1200",
                shifts.get(0).endedAt().plusMinutes(5));

        NozzleScore score = NozzleScorer.score(NOZZLE, shifts, List.of(lowering), ZONE);

        assertThat(score.readingJumpCount()).isZero();
        assertThat(score.amendmentCount()).isEqualTo(1);
        assertThat(score.score()).isEqualTo(NozzleScorer.LOWERING_AMENDMENT_POINTS);
    }

    @Test
    void amendmentsScoreFifteenEachOrTwentyFiveWhenTheyLowerTheMeterUpToFifty() {
        OffsetDateTime at = FIRST_SHIFT_START.minusDays(1);
        assertThat(NozzleScorer.score(NOZZLE, List.of(), List.of(amendment("RESET", "5000", "0", at)), ZONE).score())
                .isEqualTo(15);
        assertThat(NozzleScorer.score(NOZZLE, List.of(), List.of(amendment("CUSTOM_READING", "100", "200", at)), ZONE).score())
                .isEqualTo(15);
        List<NozzleReadingAdjustment> many = List.of(amendment("CUSTOM_READING", "200", "100", at),
                amendment("CUSTOM_READING", "200", "100", at), amendment("RESET", "200", "0", at));
        NozzleScore score = NozzleScorer.score(NOZZLE, List.of(), many, ZONE);
        assertThat(score.score()).isEqualTo(NozzleScorer.AMENDMENT_MAX_POINTS);
        assertThat(score.band()).isEqualTo(RadarBand.WATCH);
    }

    @Test
    void shiftsUnderHalfOrOverDoubleTheSlotMedianAreOutliers() {
        List<NozzleShiftRow> shifts = new ArrayList<>(steadyShifts(6, "1000", "500"));
        shifts.add(shift(6, "4000", "4100", "100"));
        shifts.add(shift(7, "4100", "5300", "1200"));

        NozzleScore score = NozzleScorer.score(NOZZLE, shifts, List.of(), ZONE);

        assertThat(score.salesOutlierCount()).isEqualTo(2);
        assertThat(score.evidence()).filteredOn(evidence -> evidence.kind() == EvidenceKind.SALES_OUTLIER)
                .allSatisfy(evidence -> assertThat(evidence.usualLitres()).isEqualByComparingTo("500"));
        assertThat(score.score()).isEqualTo(20);
    }

    @Test
    void fewerThanFiveShiftsWithNothingFoundIsNotEnoughData() {
        NozzleScore score = NozzleScorer.score(NOZZLE, steadyShifts(3, "1000", "500"), List.of(), ZONE);

        assertThat(score.band()).isEqualTo(RadarBand.NOT_ENOUGH_DATA);
        assertThat(score.reasons()).extracting(ScoreReason::code).contains("NOT_ENOUGH_SHIFTS");
    }

    private static List<NozzleShiftRow> steadyShifts(int count, String firstReading, String litres) {
        List<NozzleShiftRow> shifts = new ArrayList<>();
        BigDecimal reading = new BigDecimal(firstReading);
        for (int i = 0; i < count; i++) {
            BigDecimal end = reading.add(new BigDecimal(litres));
            shifts.add(shift(i, reading.toPlainString(), end.toPlainString(), litres));
            reading = end;
        }
        return shifts;
    }

    private static NozzleShiftRow shift(int index, String start, String end, String litres) {
        OffsetDateTime startedAt = FIRST_SHIFT_START.plusDays(index);
        return new NozzleShiftRow(NOZZLE.nozzleId(), 100L + index, startedAt.toLocalDate(), 1L, startedAt,
                startedAt.plusHours(8), new BigDecimal(start), new BigDecimal(end), new BigDecimal(litres));
    }

    private static NozzleReadingAdjustment amendment(String type, String previous, String next, OffsetDateTime at) {
        return NozzleReadingAdjustment.builder().id(1L).pumpId(1L).nozzleId(NOZZLE.nozzleId()).adjustmentType(type)
                .fuelType("PETROL").previousReading(new BigDecimal(previous)).newReading(new BigDecimal(next))
                .reason("Meter replaced").recordedByUserId(2L).createdAt(at).build();
    }
}
