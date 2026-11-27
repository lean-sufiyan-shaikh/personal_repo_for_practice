package com.ppms.inventory;

import com.ppms.inventory.WetStockReconciliationResponse.Period;
import com.ppms.inventory.WetStockReconciliationResponse.PeriodStatus;
import com.ppms.inventory.WetStockReconciliationResponse.Summary;
import com.ppms.inventory.WetStockReconciliationResponse.SummaryStatus;
import com.ppms.inventory.WetStockReconciliationResponse.TankReconciliation;
import com.ppms.pump.UndergroundTank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Reconciles one tank from dip to dip: opening (the previous closing dip) + deliveries − meter sales − removals =
 * expected closing, compared with the last dip of the closing day. Deliveries and meter sales count between the two
 * dips' exact moments; removals, recorded by day only, count over the days after the opening dip's day.
 */
final class WetStockReconciliationCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int LITRES_SCALE = 2;
    private static final int PERCENT_SCALE = 2;

    private WetStockReconciliationCalculator() {
    }

    record Dip(OffsetDateTime checkedAt, BigDecimal litres) {
    }

    record TimedLitres(OffsetDateTime at, BigDecimal litres) {
    }

    /**
     * One tank's figures. closingDipByDay holds the last dip of each business day and may start before the range,
     * so the first period in the range has its opening dip. stockShortfalls are litres shifts sold beyond book stock.
     */
    record TankFigures(NavigableMap<LocalDate, Dip> closingDipByDay,
                       List<TimedLitres> deliveries,
                       Map<LocalDate, BigDecimal> removedByDay,
                       TankMeterSales meterSales,
                       List<TimedLitres> stockShortfalls) {
    }

    static TankReconciliation reconcile(UndergroundTank tank, TankFigures figures, LocalDate from, LocalDate to) {
        var openingDip = figures.closingDipByDay().lowerEntry(from);
        LocalDate previousDipDay = openingDip != null ? openingDip.getKey() : null;
        Dip previousDip = openingDip != null ? openingDip.getValue() : null;

        List<Period> periods = new ArrayList<>();
        BigDecimal cumulativeVariance = BigDecimal.ZERO;
        BigDecimal cumulativeSold = BigDecimal.ZERO;
        int flagged = 0;
        int reconciled = 0;
        for (var entry : figures.closingDipByDay().subMap(from, true, to, true).entrySet()) {
            LocalDate dipDay = entry.getKey();
            Dip closing = entry.getValue();
            if (previousDipDay == null) {
                periods.add(needsOpeningDip(dipDay, closing.litres()));
            } else {
                OffsetDateTime openedAt = previousDip.checkedAt();
                OffsetDateTime closedAt = closing.checkedAt();
                BigDecimal delivered = sumBetween(figures.deliveries(), openedAt, closedAt);
                BigDecimal sold = figures.meterSales().soldBetween(openedAt, closedAt);
                BigDecimal removed = sumOver(figures.removedByDay(), previousDipDay.plusDays(1), dipDay);
                BigDecimal shortfall = sumBetween(figures.stockShortfalls(), openedAt, closedAt);
                BigDecimal expected = previousDip.litres().add(delivered).subtract(sold).subtract(removed);
                BigDecimal variance = closing.litres().subtract(expected);
                boolean withinTolerance = variance.abs().compareTo(tank.getDipTolerance()) <= 0;
                cumulativeVariance = cumulativeVariance.add(variance);
                cumulativeSold = cumulativeSold.add(sold);
                reconciled++;
                if (!withinTolerance) {
                    flagged++;
                }
                periods.add(new Period(previousDipDay.plusDays(1), dipDay, (int) ChronoUnit.DAYS.between(previousDipDay, dipDay),
                        PeriodStatus.RECONCILED, litres(previousDip.litres()), litres(delivered), litres(sold),
                        litres(removed), litres(expected), litres(closing.litres()), litres(variance),
                        percentOf(variance, sold), withinTolerance, litres(cumulativeVariance),
                        percentOf(cumulativeVariance, cumulativeSold), shortfall.signum() > 0 ? litres(shortfall) : null));
            }
            previousDipDay = dipDay;
            previousDip = closing;
        }

        BigDecimal cumulativePercent = percentOf(cumulativeVariance, cumulativeSold);
        Summary summary = new Summary(litres(cumulativeSold), litres(cumulativeVariance), cumulativePercent,
                reconciled, flagged, summaryStatus(reconciled, cumulativePercent, tank.effectiveLossAlertPercent()));
        return new TankReconciliation(tank.getId(), tank.getTankIdentifier(), tank.getFuelType().name(),
                tank.getDipTolerance(), tank.effectiveLossAlertPercent(), summary, periods);
    }

    private static Period needsOpeningDip(LocalDate dipDay, BigDecimal closing) {
        return new Period(dipDay, dipDay, 1, PeriodStatus.NEEDS_OPENING_DIP, null, null, null, null, null,
                litres(closing), null, null, null, null, null, null);
    }

    private static SummaryStatus summaryStatus(int reconciled, BigDecimal variancePercent, BigDecimal limitPercent) {
        if (reconciled == 0) {
            return SummaryStatus.NO_DATA;
        }
        if (variancePercent == null || variancePercent.abs().compareTo(limitPercent) <= 0) {
            return SummaryStatus.WITHIN_LIMITS;
        }
        return variancePercent.signum() < 0 ? SummaryStatus.LOSS_ABOVE_LIMIT : SummaryStatus.GAIN_ABOVE_LIMIT;
    }

    /** Litres timed after the opening dip and up to and including the closing dip. */
    private static BigDecimal sumBetween(List<TimedLitres> timed, OffsetDateTime after, OffsetDateTime upTo) {
        return timed.stream()
                .filter(item -> item.at().isAfter(after) && !item.at().isAfter(upTo))
                .map(TimedLitres::litres)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumOver(Map<LocalDate, BigDecimal> byDay, LocalDate first, LocalDate last) {
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            total = total.add(byDay.getOrDefault(day, BigDecimal.ZERO));
        }
        return total;
    }

    private static BigDecimal percentOf(BigDecimal variance, BigDecimal sold) {
        if (sold.signum() == 0) {
            return null;
        }
        return variance.multiply(HUNDRED).divide(sold, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal litres(BigDecimal value) {
        return value.setScale(LITRES_SCALE, RoundingMode.HALF_UP);
    }
}
