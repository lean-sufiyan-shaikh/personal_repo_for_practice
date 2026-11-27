package com.ppms.radar;

import com.ppms.radar.LeakageRadarResponse.PersonScore;
import com.ppms.radar.LeakageRadarResponse.ShortageTrend;
import com.ppms.shift.DiscrepancyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores a person out of 100: up to 60 for a shortage pattern on their own shifts compared with the rest of the
 * pump, and up to 40 for voids, reassignments and rejections beyond one per 30 days.
 */
final class PersonScorer {

    static final int MINIMUM_SHIFTS = 5;
    /** Shortages of this many rupees or less are rounding and are not counted. */
    static final BigDecimal SHORTAGE_FLOOR = new BigDecimal("20");
    static final int RATE_MAX_POINTS = 30;
    static final double RATE_POINTS_PER_PERCENT_ABOVE = 0.6;
    static final int AMOUNT_MAX_POINTS = 20;
    static final BigDecimal RUPEES_PER_SHIFT_PER_POINT = new BigDecimal("25");
    static final int RISING_POINTS = 10;
    static final int CORRECTION_MAX_POINTS = 40;
    static final int POINTS_PER_EXTRA_CORRECTION = 8;
    static final int DAYS_PER_EXPECTED_CORRECTION = 30;
    static final int MAX_EVIDENCE = 25;

    private PersonScorer() {
    }

    record Person(Long userId, String name, String role) {
    }

    /** Shifts and counted shortages of a group of operators. */
    record ShortageTally(int shifts, int shortShifts, BigDecimal shortAmount) {

        static ShortageTally of(List<ShiftOutcomeRow> shifts) {
            List<ShiftOutcomeRow> shortages = shifts.stream().filter(PersonScorer::isCountedShortage).toList();
            return new ShortageTally(shifts.size(), shortages.size(), shortages.stream()
                    .map(ShiftOutcomeRow::discrepancyAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        ShortageTally minus(ShortageTally other) {
            return new ShortageTally(shifts - other.shifts, shortShifts - other.shortShifts,
                    shortAmount.subtract(other.shortAmount));
        }

        double shortPercent() {
            return shifts == 0 ? 0 : shortShifts * 100.0 / shifts;
        }

        BigDecimal shortPerShift() {
            return shifts == 0 ? BigDecimal.ZERO : shortAmount.divide(BigDecimal.valueOf(shifts), 2, RoundingMode.HALF_UP);
        }
    }

    static boolean isCountedShortage(ShiftOutcomeRow shift) {
        return shift.discrepancyType() == DiscrepancyType.SHORT && shift.discrepancyAmount() != null
                && shift.discrepancyAmount().compareTo(SHORTAGE_FLOOR) > 0;
    }

    static PersonScore score(Person person, List<ShiftOutcomeRow> ownShifts, ShortageTally restOfPump,
                             List<RadarEvidence> corrections, RadarPeriod period) {
        ShortageTally own = ShortageTally.of(ownShifts);
        List<ScoreReason> reasons = new ArrayList<>();
        boolean enoughShifts = own.shifts() >= MINIMUM_SHIFTS;
        ShortageTrend trend = trendOf(ownShifts, period);
        int shortagePoints = 0;
        if (enoughShifts) {
            shortagePoints += addRateReason(reasons, own, restOfPump);
            shortagePoints += addAmountReason(reasons, own, restOfPump);
            shortagePoints += addTrendReason(reasons, ownShifts, period, trend);
        } else if (own.shifts() > 0) {
            reasons.add(new ScoreReason("NOT_ENOUGH_SHIFTS", 0, 0,
                    Map.of("shifts", own.shifts(), "minimum", MINIMUM_SHIFTS)));
        }
        int correctionPoints = addCorrectionReason(reasons, corrections.size(), period);

        int score = Math.min(100, shortagePoints + correctionPoints);
        boolean notEnoughData = own.shifts() > 0 && !enoughShifts && correctionPoints == 0;
        List<RadarEvidence> evidence = new ArrayList<>(corrections);
        ownShifts.stream().filter(PersonScorer::isCountedShortage)
                .map(shift -> RadarEvidence.money(EvidenceKind.SHORT_SHIFT, shift.shiftDate(), shift.discrepancyAmount(),
                        shift.shiftId(), null, null))
                .forEach(evidence::add);
        return new PersonScore(person.userId(), person.name(), person.role(), score,
                notEnoughData ? RadarBand.NOT_ENOUGH_DATA : RadarBand.of(score), own.shifts(), own.shortShifts(),
                own.shortAmount().setScale(2, RoundingMode.HALF_UP), trend, corrections.size(), reasons,
                newestFirst(evidence));
    }

    private static int addRateReason(List<ScoreReason> reasons, ShortageTally own, ShortageTally rest) {
        double above = own.shortPercent() - rest.shortPercent();
        int points = clamp((int) Math.round(above * RATE_POINTS_PER_PERCENT_ABOVE), RATE_MAX_POINTS);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("shortShifts", own.shortShifts());
        params.put("shifts", own.shifts());
        params.put("percent", oneDecimal(own.shortPercent()));
        params.put("restPercent", oneDecimal(rest.shortPercent()));
        reasons.add(new ScoreReason("SHORTAGE_RATE", points, RATE_MAX_POINTS, params));
        return points;
    }

    private static int addAmountReason(List<ScoreReason> reasons, ShortageTally own, ShortageTally rest) {
        BigDecimal above = own.shortPerShift().subtract(rest.shortPerShift());
        int points = clamp(above.divide(RUPEES_PER_SHIFT_PER_POINT, 0, RoundingMode.HALF_UP).intValue(), AMOUNT_MAX_POINTS);
        reasons.add(new ScoreReason("SHORTAGE_AMOUNT", points, AMOUNT_MAX_POINTS,
                Map.of("perShift", own.shortPerShift(), "restPerShift", rest.shortPerShift(),
                        "total", own.shortAmount().setScale(2, RoundingMode.HALF_UP))));
        return points;
    }

    private static int addTrendReason(List<ScoreReason> reasons, List<ShiftOutcomeRow> shifts, RadarPeriod period,
                                      ShortageTrend trend) {
        int points = trend == ShortageTrend.RISING ? RISING_POINTS : 0;
        reasons.add(new ScoreReason("SHORTAGE_TREND", points, RISING_POINTS,
                Map.of("earlier", shortagesBefore(shifts, period), "recent", shortagesFrom(shifts, period),
                        "trendCode", trend.name())));
        return points;
    }

    private static int addCorrectionReason(List<ScoreReason> reasons, int corrections, RadarPeriod period) {
        int expected = period.days() / DAYS_PER_EXPECTED_CORRECTION;
        int points = clamp((corrections - expected) * POINTS_PER_EXTRA_CORRECTION, CORRECTION_MAX_POINTS);
        reasons.add(new ScoreReason("CORRECTIONS", points, CORRECTION_MAX_POINTS,
                Map.of("count", corrections, "expected", expected)));
        return points;
    }

    /** Rising when the second half of the period has more shortages than the first and at least two. */
    static ShortageTrend trendOf(List<ShiftOutcomeRow> shifts, RadarPeriod period) {
        long earlier = shortagesBefore(shifts, period);
        long recent = shortagesFrom(shifts, period);
        if (recent > earlier && recent >= 2) {
            return ShortageTrend.RISING;
        }
        return recent < earlier ? ShortageTrend.FALLING : ShortageTrend.STEADY;
    }

    private static long shortagesBefore(List<ShiftOutcomeRow> shifts, RadarPeriod period) {
        return shifts.stream().filter(PersonScorer::isCountedShortage)
                .filter(shift -> shift.shiftDate().isBefore(period.secondHalfStart())).count();
    }

    private static long shortagesFrom(List<ShiftOutcomeRow> shifts, RadarPeriod period) {
        return shifts.stream().filter(PersonScorer::isCountedShortage)
                .filter(shift -> !shift.shiftDate().isBefore(period.secondHalfStart())).count();
    }

    static List<RadarEvidence> newestFirst(List<RadarEvidence> evidence) {
        return evidence.stream()
                .sorted(Comparator.comparing(RadarEvidence::date).reversed())
                .limit(MAX_EVIDENCE)
                .toList();
    }

    static int clamp(int points, int max) {
        return Math.max(0, Math.min(max, points));
    }

    private static BigDecimal oneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
