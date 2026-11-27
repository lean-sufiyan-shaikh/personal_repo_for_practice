package com.ppms.radar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Rule-based "worth a look" scores (0–100) per person, nozzle and tank over the last 30 or 90 days, each with the
 * rules that gave its points and the events behind them. Lists are sorted highest score first.
 */
public record LeakageRadarResponse(int periodDays, LocalDate from, LocalDate to, int minimumShifts,
                                   List<PersonScore> people, List<NozzleScore> nozzles, List<TankScore> tanks) {

    public enum ShortageTrend {
        RISING,
        STEADY,
        FALLING
    }

    /** shortShiftCount and shortAmount count only shortages above the rounding floor. */
    public record PersonScore(Long userId, String name, String role, int score, RadarBand band, int shiftCount,
                              int shortShiftCount, BigDecimal shortAmount, ShortageTrend trend, int correctionCount,
                              List<ScoreReason> reasons, List<RadarEvidence> evidence) {
    }

    public record NozzleScore(Long nozzleId, int nozzleNumber, String duName, String fuelType, int score,
                              RadarBand band, int shiftCount, int amendmentCount, int readingJumpCount,
                              int salesOutlierCount, List<ScoreReason> reasons, List<RadarEvidence> evidence) {
    }

    /** weeks are the 7-day stretches ending on the period's last day that have at least one reconciled dip. */
    public record TankScore(Long tankId, String tankIdentifier, String fuelType, int score, RadarBand band,
                            BigDecimal lossAlertPercent, BigDecimal varianceLitres, BigDecimal variancePercent,
                            List<WeekFigure> weeks, List<ScoreReason> reasons, List<RadarEvidence> evidence) {
    }

    public record WeekFigure(LocalDate weekStart, LocalDate weekEnd, BigDecimal soldLitres, BigDecimal varianceLitres,
                             BigDecimal variancePercent, boolean lossAboveLimit) {
    }
}
