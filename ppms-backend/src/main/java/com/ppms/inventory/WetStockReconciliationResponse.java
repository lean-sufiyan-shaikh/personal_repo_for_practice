package com.ppms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Daily wet-stock reconciliation of each tank over a date range. Litres are ambient (no temperature correction),
 * so variances include the effect of temperature on fuel volume.
 */
public record WetStockReconciliationResponse(LocalDate from, LocalDate to, List<TankReconciliation> tanks) {

    public record TankReconciliation(Long tankId, String tankIdentifier, String fuelType, BigDecimal dipTolerance,
                                     BigDecimal lossAlertPercent, Summary summary, List<Period> periods) {
    }

    /**
     * The tank over the whole range. variancePercent is the cumulative variance as a share of litres sold;
     * null when nothing was sold in the reconciled periods.
     */
    public record Summary(BigDecimal salesLitres, BigDecimal varianceLitres, BigDecimal variancePercent,
                          int reconciledPeriodCount, int flaggedPeriodCount, SummaryStatus status) {
    }

    /**
     * One reconciled stretch ending on a day with a dip. Days without a dip are merged into the next day that has one,
     * so a period can span several days. A NEEDS_OPENING_DIP period has only its closing dip; every other figure is null.
     */
    public record Period(LocalDate periodStart, LocalDate periodEnd, int days, PeriodStatus status,
                         BigDecimal openingLitres, BigDecimal deliveredLitres, BigDecimal soldLitres,
                         BigDecimal removedLitres, BigDecimal expectedClosingLitres, BigDecimal closingLitres,
                         BigDecimal varianceLitres, BigDecimal variancePercent, Boolean withinTolerance,
                         BigDecimal cumulativeVarianceLitres, BigDecimal cumulativeVariancePercent) {
    }

    public enum PeriodStatus {
        RECONCILED,
        NEEDS_OPENING_DIP
    }

    public enum SummaryStatus {
        WITHIN_LIMITS,
        LOSS_ABOVE_LIMIT,
        GAIN_ABOVE_LIMIT,
        NO_DATA
    }
}
