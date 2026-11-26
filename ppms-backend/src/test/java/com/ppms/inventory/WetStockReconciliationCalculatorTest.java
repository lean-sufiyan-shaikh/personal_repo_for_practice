package com.ppms.inventory;

import com.ppms.fuel.FuelType;
import com.ppms.inventory.WetStockReconciliationCalculator.TankDailyFigures;
import com.ppms.inventory.WetStockReconciliationResponse.Period;
import com.ppms.inventory.WetStockReconciliationResponse.PeriodStatus;
import com.ppms.inventory.WetStockReconciliationResponse.SummaryStatus;
import com.ppms.inventory.WetStockReconciliationResponse.TankReconciliation;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class WetStockReconciliationCalculatorTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate DAY_2 = DAY_1.plusDays(1);
    private static final LocalDate DAY_3 = DAY_1.plusDays(2);
    private static final LocalDate DAY_4 = DAY_1.plusDays(3);

    private final TreeMap<LocalDate, BigDecimal> dips = new TreeMap<>();
    private final Map<LocalDate, BigDecimal> delivered = new HashMap<>();
    private final Map<LocalDate, BigDecimal> sold = new HashMap<>();
    private final Map<LocalDate, BigDecimal> removed = new HashMap<>();

    @Test
    void normalDayExpectsOpeningLessSalesAndReportsTheVarianceAsShareOfSales() {
        dips.put(DAY_1, litres("10000"));
        sold.put(DAY_2, litres("1000"));
        dips.put(DAY_2, litres("8990"));

        Period period = onlyPeriod(reconcile(tank("20", null), DAY_2, DAY_2));

        assertThat(period.status()).isEqualTo(PeriodStatus.RECONCILED);
        assertThat(period.periodStart()).isEqualTo(DAY_2);
        assertThat(period.days()).isEqualTo(1);
        assertThat(period.openingLitres()).isEqualByComparingTo("10000");
        assertThat(period.soldLitres()).isEqualByComparingTo("1000");
        assertThat(period.expectedClosingLitres()).isEqualByComparingTo("9000");
        assertThat(period.closingLitres()).isEqualByComparingTo("8990");
        assertThat(period.varianceLitres()).isEqualByComparingTo("-10");
        assertThat(period.variancePercent()).isEqualByComparingTo("-1.00");
        assertThat(period.withinTolerance()).isTrue();
    }

    @Test
    void daysWithoutADipAreMergedIntoTheNextDayThatHasOne() {
        dips.put(DAY_1, litres("10000"));
        sold.put(DAY_2, litres("400"));
        sold.put(DAY_3, litres("600"));
        dips.put(DAY_3, litres("9000"));

        Period period = onlyPeriod(reconcile(tank("20", null), DAY_2, DAY_3));

        assertThat(period.periodStart()).isEqualTo(DAY_2);
        assertThat(period.periodEnd()).isEqualTo(DAY_3);
        assertThat(period.days()).isEqualTo(2);
        assertThat(period.soldLitres()).isEqualByComparingTo("1000");
        assertThat(period.varianceLitres()).isEqualByComparingTo("0");
    }

    @Test
    void firstDipWithNothingBeforeItNeedsAnOpeningDip() {
        dips.put(DAY_1, litres("10000"));
        sold.put(DAY_2, litres("1000"));
        dips.put(DAY_2, litres("9000"));

        TankReconciliation result = reconcile(tank("20", null), DAY_1, DAY_2);

        Period first = result.periods().get(0);
        assertThat(first.status()).isEqualTo(PeriodStatus.NEEDS_OPENING_DIP);
        assertThat(first.closingLitres()).isEqualByComparingTo("10000");
        assertThat(first.varianceLitres()).isNull();
        assertThat(first.withinTolerance()).isNull();
        assertThat(result.periods().get(1).status()).isEqualTo(PeriodStatus.RECONCILED);
        assertThat(result.summary().reconciledPeriodCount()).isEqualTo(1);
    }

    @Test
    void deliveryAndDrainInsideThePeriodAreAddedAndTakenOff() {
        dips.put(DAY_1, litres("2000"));
        delivered.put(DAY_2, litres("8000"));
        removed.put(DAY_2, litres("20"));
        sold.put(DAY_2, litres("1500"));
        dips.put(DAY_2, litres("8485"));

        Period period = onlyPeriod(reconcile(tank("20", null), DAY_2, DAY_2));

        assertThat(period.deliveredLitres()).isEqualByComparingTo("8000");
        assertThat(period.removedLitres()).isEqualByComparingTo("20");
        assertThat(period.expectedClosingLitres()).isEqualByComparingTo("8480");
        assertThat(period.varianceLitres()).isEqualByComparingTo("5");
    }

    @Test
    void periodWhoseVarianceIsBeyondTheDipToleranceIsFlagged() {
        dips.put(DAY_1, litres("10000"));
        sold.put(DAY_2, litres("1000"));
        dips.put(DAY_2, litres("8975"));

        TankReconciliation result = reconcile(tank("20", null), DAY_2, DAY_2);

        assertThat(onlyPeriod(result).withinTolerance()).isFalse();
        assertThat(result.summary().flaggedPeriodCount()).isEqualTo(1);
    }

    @Test
    void cumulativeVarianceRunsAcrossPeriodsAsShareOfAllLitresSold() {
        dips.put(DAY_1, litres("10000"));
        sold.put(DAY_2, litres("1000"));
        dips.put(DAY_2, litres("8990"));
        sold.put(DAY_3, litres("1000"));
        dips.put(DAY_3, litres("7996"));
        sold.put(DAY_4, litres("2000"));
        dips.put(DAY_4, litres("5992"));

        TankReconciliation result = reconcile(tank("50", null), DAY_2, DAY_4);

        assertThat(result.periods()).extracting(Period::cumulativeVarianceLitres)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(litres("-10"), litres("-4"), litres("-8"));
        assertThat(result.periods().get(2).cumulativeVariancePercent()).isEqualByComparingTo("-0.20");
        assertThat(result.summary().salesLitres()).isEqualByComparingTo("4000");
        assertThat(result.summary().varianceLitres()).isEqualByComparingTo("-8");
        assertThat(result.summary().variancePercent()).isEqualByComparingTo("-0.20");
        assertThat(result.summary().status()).isEqualTo(SummaryStatus.WITHIN_LIMITS);
    }

    @Test
    void lossOrGainBeyondTheTanksLimitSetsTheSummaryStatus() {
        dips.put(DAY_1, litres("10000"));
        sold.put(DAY_2, litres("1000"));
        dips.put(DAY_2, litres("8990"));

        assertThat(reconcile(tank("50", null), DAY_2, DAY_2).summary().status()).isEqualTo(SummaryStatus.LOSS_ABOVE_LIMIT);
        assertThat(reconcile(tank("50", "2"), DAY_2, DAY_2).summary().status()).isEqualTo(SummaryStatus.WITHIN_LIMITS);

        dips.put(DAY_2, litres("9010"));
        assertThat(reconcile(tank("50", null), DAY_2, DAY_2).summary().status()).isEqualTo(SummaryStatus.GAIN_ABOVE_LIMIT);
    }

    @Test
    void tankWithoutDipsInTheRangeHasNoData() {
        TankReconciliation result = reconcile(tank("20", null), DAY_1, DAY_4);

        assertThat(result.periods()).isEmpty();
        assertThat(result.summary().status()).isEqualTo(SummaryStatus.NO_DATA);
        assertThat(result.summary().variancePercent()).isNull();
    }

    private TankReconciliation reconcile(UndergroundTank tank, LocalDate from, LocalDate to) {
        return WetStockReconciliationCalculator.reconcile(tank, new TankDailyFigures(dips, delivered, sold, removed), from, to);
    }

    private static Period onlyPeriod(TankReconciliation result) {
        assertThat(result.periods()).hasSize(1);
        return result.periods().get(0);
    }

    private static UndergroundTank tank(String dipTolerance, String lossAlertPercent) {
        return UndergroundTank.builder()
                .id(7L)
                .tankIdentifier("T1")
                .fuelType(FuelType.PETROL)
                .dipTolerance(new BigDecimal(dipTolerance))
                .lossAlertPercent(lossAlertPercent == null ? null : new BigDecimal(lossAlertPercent))
                .status(TankStatus.ACTIVE)
                .build();
    }

    private static BigDecimal litres(String value) {
        return new BigDecimal(value);
    }
}
