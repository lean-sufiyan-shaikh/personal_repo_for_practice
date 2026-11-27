package com.ppms.radar;

import com.ppms.inventory.WetStockReconciliationResponse.Period;
import com.ppms.inventory.WetStockReconciliationResponse.PeriodStatus;
import com.ppms.inventory.WetStockReconciliationResponse.TankReconciliation;
import com.ppms.radar.LeakageRadarResponse.TankScore;
import com.ppms.radar.LeakageRadarResponse.WeekFigure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scores a tank out of 100 from its wet-stock reconciliation grouped into 7-day weeks ending on the period's last
 * day. A loss week lost more than the tank's loss alert limit (a share of litres sold). Points: up to 40 for the
 * share of weeks that were loss weeks, 10 for each loss week in a row after the first (up to 30), and 30 when the
 * whole period lost more than the limit (15 when it lost more than half of it).
 */
final class TankScorer {

    static final int MINIMUM_WEEKS = 3;
    static final int SHARE_MAX_POINTS = 40;
    static final int STREAK_POINTS_PER_WEEK = 10;
    static final int STREAK_MAX_POINTS = 30;
    static final int PERIOD_LOSS_POINTS = 30;
    static final int PERIOD_HALF_LOSS_POINTS = 15;
    private static final int DAYS_PER_WEEK = 7;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private TankScorer() {
    }

    static TankScore score(TankReconciliation tank, LocalDate lastDay) {
        BigDecimal limit = tank.lossAlertPercent();
        List<WeekFigure> weeks = weeksOf(tank.periods(), lastDay, limit);
        BigDecimal periodPercent = tank.summary().variancePercent();
        List<ScoreReason> reasons = new ArrayList<>();
        List<RadarEvidence> evidence = new ArrayList<>();
        weeks.stream().filter(WeekFigure::lossAboveLimit)
                .map(week -> RadarEvidence.litres(EvidenceKind.LOSS_WEEK, week.weekEnd(), week.varianceLitres(),
                        week.soldLitres(), week.variancePercent(), null))
                .forEach(evidence::add);

        if (weeks.size() < MINIMUM_WEEKS) {
            reasons.add(new ScoreReason("NOT_ENOUGH_WEEKS", 0, 0, Map.of("weeks", weeks.size(), "minimum", MINIMUM_WEEKS)));
            return new TankScore(tank.tankId(), tank.tankIdentifier(), tank.fuelType(), 0, RadarBand.NOT_ENOUGH_DATA,
                    limit, tank.summary().varianceLitres(), periodPercent, weeks, reasons, PersonScorer.newestFirst(evidence));
        }

        long lossWeeks = weeks.stream().filter(WeekFigure::lossAboveLimit).count();
        int sharePoints = (int) Math.round(SHARE_MAX_POINTS * (double) lossWeeks / weeks.size());
        reasons.add(new ScoreReason("LOSS_WEEKS", sharePoints, SHARE_MAX_POINTS,
                Map.of("lossWeeks", lossWeeks, "weeks", weeks.size(), "limitPercent", limit.stripTrailingZeros())));

        int streak = longestLossStreak(weeks);
        int streakPoints = PersonScorer.clamp((streak - 1) * STREAK_POINTS_PER_WEEK, STREAK_MAX_POINTS);
        reasons.add(new ScoreReason("LOSS_STREAK", streakPoints, STREAK_MAX_POINTS, Map.of("weeks", streak)));

        int periodPoints = periodLossPoints(periodPercent, limit);
        Map<String, Object> periodParams = periodPercent == null
                ? Map.of("limitPercent", limit.stripTrailingZeros())
                : Map.of("percent", periodPercent, "limitPercent", limit.stripTrailingZeros());
        reasons.add(new ScoreReason("PERIOD_LOSS", periodPoints, PERIOD_LOSS_POINTS, periodParams));

        int score = Math.min(100, sharePoints + streakPoints + periodPoints);
        return new TankScore(tank.tankId(), tank.tankIdentifier(), tank.fuelType(), score, RadarBand.of(score), limit,
                tank.summary().varianceLitres(), periodPercent, weeks, reasons, PersonScorer.newestFirst(evidence));
    }

    /** Reconciled periods summed into the week their closing dip falls in; weeks without one are left out. */
    static List<WeekFigure> weeksOf(List<Period> periods, LocalDate lastDay, BigDecimal limitPercent) {
        TreeMap<Long, BigDecimal> soldByWeeksBack = new TreeMap<>();
        Map<Long, BigDecimal> varianceByWeeksBack = new TreeMap<>();
        for (Period period : periods) {
            if (period.status() != PeriodStatus.RECONCILED) {
                continue;
            }
            long weeksBack = ChronoUnit.DAYS.between(period.periodEnd(), lastDay) / DAYS_PER_WEEK;
            soldByWeeksBack.merge(weeksBack, period.soldLitres(), BigDecimal::add);
            varianceByWeeksBack.merge(weeksBack, period.varianceLitres(), BigDecimal::add);
        }
        List<WeekFigure> weeks = new ArrayList<>();
        for (var entry : soldByWeeksBack.descendingMap().entrySet()) {
            LocalDate weekEnd = lastDay.minusDays(entry.getKey() * DAYS_PER_WEEK);
            BigDecimal sold = entry.getValue();
            BigDecimal variance = varianceByWeeksBack.get(entry.getKey());
            BigDecimal percent = sold.signum() == 0 ? null : variance.multiply(HUNDRED).divide(sold, 2, RoundingMode.HALF_UP);
            boolean loss = percent != null && percent.negate().compareTo(limitPercent) > 0;
            weeks.add(new WeekFigure(weekEnd.minusDays(DAYS_PER_WEEK - 1L), weekEnd, sold.setScale(2, RoundingMode.HALF_UP),
                    variance.setScale(2, RoundingMode.HALF_UP), percent, loss));
        }
        return weeks;
    }

    /** Loss weeks back to back; a week with no reconciled dip in between ends the run. */
    static int longestLossStreak(List<WeekFigure> weeks) {
        int longest = 0;
        int current = 0;
        LocalDate previousWeekEnd = null;
        for (WeekFigure week : weeks) {
            boolean follows = previousWeekEnd != null && previousWeekEnd.plusDays(DAYS_PER_WEEK).equals(week.weekEnd());
            current = week.lossAboveLimit() ? (follows ? current + 1 : 1) : 0;
            longest = Math.max(longest, current);
            previousWeekEnd = week.weekEnd();
        }
        return longest;
    }

    private static int periodLossPoints(BigDecimal periodPercent, BigDecimal limit) {
        if (periodPercent == null || periodPercent.signum() >= 0) {
            return 0;
        }
        BigDecimal loss = periodPercent.negate();
        if (loss.compareTo(limit) > 0) {
            return PERIOD_LOSS_POINTS;
        }
        return loss.compareTo(limit.divide(TWO, 4, RoundingMode.HALF_UP)) > 0 ? PERIOD_HALF_LOSS_POINTS : 0;
    }
}
