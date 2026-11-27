package com.ppms.radar;

import com.ppms.adjustment.NozzleReadingAdjustment;
import com.ppms.radar.LeakageRadarResponse.NozzleScore;
import com.ppms.shift.MeterRollover;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Scores a nozzle out of 100: meter amendments (15 points each, 25 when a typed reading lowered the meter, up to 50),
 * readings that jump between one shift's close and the next shift's open without an amendment or a rollover
 * (20 each, up to 40), and shifts whose litres are under half or over double the nozzle's median for the same
 * shift slot (10 each, up to 30).
 */
final class NozzleScorer {

    static final int AMENDMENT_POINTS = 15;
    static final int LOWERING_AMENDMENT_POINTS = 25;
    static final int AMENDMENT_MAX_POINTS = 50;
    static final int JUMP_POINTS = 20;
    static final int JUMP_MAX_POINTS = 40;
    static final int OUTLIER_POINTS = 10;
    static final int OUTLIER_MAX_POINTS = 30;
    /** A slot needs this many shifts before its median is trusted. */
    static final int MINIMUM_SLOT_SHIFTS = 5;
    static final BigDecimal LOW_SHARE_OF_MEDIAN = new BigDecimal("0.5");
    static final BigDecimal HIGH_SHARE_OF_MEDIAN = new BigDecimal("2");
    /** Gaps up to this many litres are rounding in the typed readings. */
    static final BigDecimal JUMP_FLOOR_LITRES = new BigDecimal("0.01");
    static final String CUSTOM_READING = "CUSTOM_READING";

    private NozzleScorer() {
    }

    record NozzleInfo(Long nozzleId, int nozzleNumber, String duName, String fuelType, BigDecimal maxMeterValue) {
    }

    static NozzleScore score(NozzleInfo nozzle, List<NozzleShiftRow> shifts, List<NozzleReadingAdjustment> amendments,
                             ZoneId zone) {
        List<RadarEvidence> evidence = new ArrayList<>();
        List<ScoreReason> reasons = new ArrayList<>();

        int lowering = 0;
        int amendmentPoints = 0;
        for (NozzleReadingAdjustment amendment : amendments) {
            boolean lowered = lowersTheMeter(amendment);
            lowering += lowered ? 1 : 0;
            amendmentPoints += lowered ? LOWERING_AMENDMENT_POINTS : AMENDMENT_POINTS;
            evidence.add(RadarEvidence.reading(EvidenceKind.METER_AMENDMENT,
                    amendment.getCreatedAt().atZoneSameInstant(zone).toLocalDate(), amendment.getPreviousReading(),
                    amendment.getNewReading(), null, amendment.getId(), amendment.getReason()));
        }
        amendmentPoints = Math.min(AMENDMENT_MAX_POINTS, amendmentPoints);
        reasons.add(new ScoreReason("METER_AMENDMENTS", amendmentPoints, AMENDMENT_MAX_POINTS,
                Map.of("count", amendments.size(), "lowering", lowering)));

        List<RadarEvidence> jumps = readingJumps(shifts, amendments, nozzle.maxMeterValue());
        evidence.addAll(jumps);
        int jumpPoints = Math.min(JUMP_MAX_POINTS, jumps.size() * JUMP_POINTS);
        reasons.add(new ScoreReason("READING_JUMPS", jumpPoints, JUMP_MAX_POINTS, Map.of("count", jumps.size())));

        List<RadarEvidence> outliers = salesOutliers(shifts);
        evidence.addAll(outliers);
        int outlierPoints = Math.min(OUTLIER_MAX_POINTS, outliers.size() * OUTLIER_POINTS);
        reasons.add(new ScoreReason("SALES_OUTLIERS", outlierPoints, OUTLIER_MAX_POINTS,
                Map.of("count", outliers.size(), "shifts", shifts.size())));

        if (shifts.size() < PersonScorer.MINIMUM_SHIFTS) {
            reasons.add(new ScoreReason("NOT_ENOUGH_SHIFTS", 0, 0,
                    Map.of("shifts", shifts.size(), "minimum", PersonScorer.MINIMUM_SHIFTS)));
        }
        int score = Math.min(100, amendmentPoints + jumpPoints + outlierPoints);
        boolean notEnoughData = shifts.size() < PersonScorer.MINIMUM_SHIFTS && score == 0;
        return new NozzleScore(nozzle.nozzleId(), nozzle.nozzleNumber(), nozzle.duName(), nozzle.fuelType(), score,
                notEnoughData ? RadarBand.NOT_ENOUGH_DATA : RadarBand.of(score), shifts.size(), amendments.size(),
                jumps.size(), outliers.size(), reasons, PersonScorer.newestFirst(evidence));
    }

    static boolean lowersTheMeter(NozzleReadingAdjustment amendment) {
        return CUSTOM_READING.equals(amendment.getAdjustmentType())
                && amendment.getNewReading().compareTo(amendment.getPreviousReading()) < 0;
    }

    /**
     * A shift that opened on a different reading than the nozzle's previous shift closed on. A meter that wrapped
     * past its maximum, or an amendment recorded between the two shifts, explains the gap and is not counted.
     */
    static List<RadarEvidence> readingJumps(List<NozzleShiftRow> shifts, List<NozzleReadingAdjustment> amendments,
                                            BigDecimal maxMeterValue) {
        List<RadarEvidence> jumps = new ArrayList<>();
        for (int i = 1; i < shifts.size(); i++) {
            NozzleShiftRow previous = shifts.get(i - 1);
            NozzleShiftRow current = shifts.get(i);
            BigDecimal gap = current.startReading().subtract(previous.endReading());
            boolean rolledOver = gap.signum() < 0 && MeterRollover.canRollOverFrom(previous.endReading(), maxMeterValue);
            if (gap.abs().compareTo(JUMP_FLOOR_LITRES) <= 0 || rolledOver || amendedBetween(amendments, previous, current)) {
                continue;
            }
            jumps.add(RadarEvidence.reading(EvidenceKind.READING_JUMP, current.shiftDate(), previous.endReading(),
                    current.startReading(), current.shiftId(), previous.shiftId(), null));
        }
        return jumps;
    }

    private static boolean amendedBetween(List<NozzleReadingAdjustment> amendments, NozzleShiftRow previous,
                                          NozzleShiftRow current) {
        return amendments.stream().anyMatch(amendment -> previous.endedAt() != null
                && !amendment.getCreatedAt().isBefore(previous.endedAt())
                && !amendment.getCreatedAt().isAfter(current.startedAt()));
    }

    /** Shifts compared with the median of the nozzle's shifts in the same slot, so a quiet night is not an outlier. */
    static List<RadarEvidence> salesOutliers(List<NozzleShiftRow> shifts) {
        Map<Long, List<NozzleShiftRow>> bySlot = shifts.stream().collect(Collectors.groupingBy(
                shift -> Objects.requireNonNullElse(shift.shiftDefinitionId(), 0L), LinkedHashMap::new, Collectors.toList()));
        List<RadarEvidence> outliers = new ArrayList<>();
        for (List<NozzleShiftRow> slot : bySlot.values()) {
            if (slot.size() < MINIMUM_SLOT_SHIFTS) {
                continue;
            }
            BigDecimal median = medianLitres(slot);
            if (median.signum() == 0) {
                continue;
            }
            for (NozzleShiftRow shift : slot) {
                if (shift.unitsSold().compareTo(median.multiply(LOW_SHARE_OF_MEDIAN)) < 0
                        || shift.unitsSold().compareTo(median.multiply(HIGH_SHARE_OF_MEDIAN)) > 0) {
                    outliers.add(RadarEvidence.litres(EvidenceKind.SALES_OUTLIER, shift.shiftDate(), shift.unitsSold(),
                            median, null, shift.shiftId()));
                }
            }
        }
        return outliers;
    }

    static BigDecimal medianLitres(List<NozzleShiftRow> shifts) {
        List<BigDecimal> litres = shifts.stream().map(NozzleShiftRow::unitsSold).sorted().toList();
        int middle = litres.size() / 2;
        BigDecimal median = litres.size() % 2 == 1 ? litres.get(middle)
                : litres.get(middle - 1).add(litres.get(middle)).divide(BigDecimal.valueOf(2), 3, RoundingMode.HALF_UP);
        return median.setScale(3, RoundingMode.HALF_UP);
    }
}
