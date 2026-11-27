package com.ppms.dayclose;

import com.ppms.cash.SafeCount;
import com.ppms.inventory.DeliveryCheckStatus;
import com.ppms.inventory.TankerDelivery;
import com.ppms.inventory.WetStockReconciliationResponse;
import com.ppms.report.DaySalesReader;
import com.ppms.shift.DiscrepancyType;
import com.ppms.shift.Shift;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.ppms.dayclose.DayChecklist.counted;
import static com.ppms.dayclose.DayChecklist.plural;

/**
 * Turns a day's figures into the owner's summary. Flag rules, worst wins:
 * <ul>
 *   <li>Sales — RED when the day's gross margin is below zero; AMBER when no shift was closed, open shifts were left
 *   out, or fuel was sold with no purchase cost.</li>
 *   <li>Cash — RED when there is no safe count or it differs from the ledger beyond the pump's tolerance; AMBER for a
 *   smaller safe difference, shifts closed short, or cash drops still pending.</li>
 *   <li>Stock — RED when a tank's day variance is outside its tolerance or a delivery was short; AMBER for an over
 *   delivery, undipped tanks, unchecked deliveries or open shortage claims.</li>
 *   <li>Credit and alerts — RED when a client is at or over the credit limit; AMBER for overdue clients or open
 *   attention items.</li>
 * </ul>
 */
final class DaySummaryBuilder {

    private DaySummaryBuilder() {
    }

    static DaySummary build(DayFigures figures, OffsetDateTime builtAt) {
        DaySummary.Sales sales = sales(figures);
        DaySummary.Cash cash = cash(figures);
        DaySummary.Stock stock = stock(figures);
        DaySummary.Credit credit = credit(figures);
        return new DaySummary(figures.businessDate(), builtAt, figures.closedShifts().size(), figures.openShifts().size(),
                DaySummary.worstOf(sales.flag(), cash.flag(), stock.flag(), credit.flag()),
                DayChecklist.warningsFor(figures), sales, cash, stock, credit);
    }

    private static DaySummary.Sales sales(DayFigures figures) {
        DaySalesReader.DaySales daySales = figures.sales();
        List<DaySummary.FuelSales> fuels = daySales.fuels().stream()
                .map(line -> new DaySummary.FuelSales(line.fuelType().name(), line.litres(), line.revenue(), line.cost(),
                        line.revenue().subtract(line.cost())))
                .toList();
        BigDecimal litres = fuels.stream().map(DaySummary.FuelSales::litres).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal revenue = sum(fuels, DaySummary.FuelSales::revenue);
        BigDecimal cost = sum(fuels, DaySummary.FuelSales::cost);
        BigDecimal fuelMargin = revenue.subtract(cost);
        BigDecimal productMargin = daySales.products().revenue().subtract(daySales.products().cost());
        BigDecimal grossMargin = fuelMargin.add(productMargin);

        SectionFlag.Builder flag = SectionFlag.builder();
        if (grossMargin.signum() < 0) {
            flag.red("SALES_NEGATIVE_MARGIN", Map.of("amount", grossMargin.negate().toPlainString()),
                    "The day sold below cost: gross margin is -₹" + grossMargin.negate().toPlainString());
        }
        if (figures.closedShifts().isEmpty()) {
            flag.amber("SALES_NO_CLOSED_SHIFTS", Map.of(), "No shift of this day was closed, so no fuel sales are counted");
        }
        int openShifts = figures.openShifts().size();
        if (openShifts > 0) {
            flag.amber("SALES_OPEN_SHIFTS_EXCLUDED", counted(openShifts),
                    plural(openShifts, "shift") + " still open and left out of these figures");
        }
        fuels.stream()
                .filter(fuel -> fuel.litres().signum() > 0 && fuel.cost().signum() == 0)
                .forEach(fuel -> flag.amber("SALES_COST_MISSING", Map.of("fuelTypeCode", fuel.fuelType()),
                        fuel.fuelType() + " was sold with no purchase cost, so its margin is overstated"));
        int shiftCount = figures.closedShifts().size();
        return new DaySummary.Sales(
                flag.orGreen("SALES_OK", counted(shiftCount), "All " + plural(shiftCount, "shift") + " of the day closed, sold above cost"),
                fuels, litres, revenue, cost, fuelMargin, daySales.products().revenue(), daySales.products().cost(),
                productMargin, grossMargin);
    }

    private static DaySummary.Cash cash(DayFigures figures) {
        List<Shift> shifts = figures.closedShifts();
        List<Shift> shortShifts = withDiscrepancy(shifts, DiscrepancyType.SHORT);
        List<Shift> overShifts = withDiscrepancy(shifts, DiscrepancyType.OVER);
        BigDecimal shortage = sum(shortShifts, Shift::getDiscrepancyAmount);
        DaySummary.SafeCountResult safeCount = figures.safeCount().map(DaySummaryBuilder::safeCountResult).orElse(null);

        SectionFlag.Builder flag = SectionFlag.builder();
        if (safeCount == null) {
            flag.red("CASH_NO_SAFE_COUNT", Map.of(), "The safe was not counted on this day");
        } else if (safeCount.beyondTolerance()) {
            flag.red("CASH_SAFE_BEYOND_TOLERANCE", safeParams(safeCount, true),
                    "The safe count was " + safeCount.varianceType() + " by ₹" + safeCount.varianceAmount().abs().toPlainString()
                            + ", beyond the ₹" + safeCount.toleranceAmount().toPlainString() + " tolerance");
        } else if (safeCount.varianceType() != null) {
            flag.amber("CASH_SAFE_SMALL_VARIANCE", safeParams(safeCount, false),
                    "The safe count was " + safeCount.varianceType() + " by ₹" + safeCount.varianceAmount().abs().toPlainString()
                            + ", within tolerance");
        }
        if (!shortShifts.isEmpty()) {
            Map<String, Object> params = counted(shortShifts.size());
            params.put("amount", shortage.toPlainString());
            flag.amber("CASH_SHIFT_SHORTAGES", params,
                    plural(shortShifts.size(), "shift") + " closed short by ₹" + shortage.toPlainString() + " in all");
        }
        DayFigures.Total pending = figures.pendingDrops();
        if (pending.count() > 0) {
            Map<String, Object> params = counted(pending.count());
            params.put("amount", pending.amount().toPlainString());
            flag.amber("CASH_PENDING_DROPS", params,
                    plural(pending.count(), "cash drop") + " of ₹" + pending.amount().toPlainString() + " not yet confirmed in the safe");
        }
        return new DaySummary.Cash(flag.orGreen("CASH_OK", Map.of(), "The safe matches the ledger and every shift balanced"),
                sum(shifts, Shift::getCashCollected), sum(shifts, Shift::getUpiCollected), sum(shifts, Shift::getCardCollected),
                sum(shifts, Shift::getFleetCardCollected), sum(shifts, Shift::getCreditTotal),
                shortage, shortShifts.size(), sum(overShifts, Shift::getDiscrepancyAmount), overShifts.size(),
                figures.confirmedDrops().count(), figures.confirmedDrops().amount(), pending.count(), safeCount,
                figures.deposits().count(), figures.deposits().amount());
    }

    private static DaySummary.Stock stock(DayFigures figures) {
        List<DaySummary.TankVariance> tanks = tankVariances(figures);
        List<DaySummary.DeliveryCheck> deliveries = figures.deliveries().stream()
                .sorted(Comparator.comparing(TankerDelivery::getDeliveryDate).thenComparing(TankerDelivery::getId))
                .map(delivery -> new DaySummary.DeliveryCheck(delivery.getId(), delivery.getInvoiceReference(),
                        delivery.getFuelType().name(), delivery.getQuantityDelivered(), delivery.getReceivedLitres(),
                        delivery.getVarianceLitres(), delivery.getCheckStatus()))
                .toList();

        SectionFlag.Builder flag = SectionFlag.builder();
        tanks.stream().filter(tank -> Boolean.FALSE.equals(tank.withinTolerance())).forEach(tank ->
                flag.red("STOCK_TANK_OUTSIDE_TOLERANCE",
                        Map.of("tankIdentifier", tank.tankIdentifier(), "litres", tank.varianceLitres().toPlainString()),
                        "Tank " + tank.tankIdentifier() + " is " + tank.varianceLitres().toPlainString() + " L off its expected stock, outside tolerance"));
        deliveries.stream().filter(delivery -> delivery.checkStatus() == DeliveryCheckStatus.SHORT).forEach(delivery ->
                flag.red("STOCK_DELIVERY_SHORT", deliveryParams(delivery),
                        "Delivery " + delivery.invoiceReference() + " arrived " + litresOf(delivery) + " L short of its invoice"));
        deliveries.stream().filter(delivery -> delivery.checkStatus() == DeliveryCheckStatus.OVER).forEach(delivery ->
                flag.amber("STOCK_DELIVERY_OVER", deliveryParams(delivery),
                        "Delivery " + delivery.invoiceReference() + " measured " + litresOf(delivery) + " L more than its invoice"));
        int tanksToDip = figures.tanksWithoutDip().size();
        if (tanksToDip > 0) {
            flag.amber("STOCK_TANKS_NOT_DIPPED", counted(tanksToDip), plural(tanksToDip, "tank") + " not dipped this day");
        }
        long unchecked = figures.uncheckedDeliveryCount();
        if (unchecked > 0) {
            flag.amber("STOCK_DELIVERIES_NOT_CHECKED", counted(unchecked),
                    plural(unchecked, "delivery", "deliveries") + " not checked against the invoice");
        }
        DayFigures.Total claims = figures.openClaims();
        if (claims.count() > 0) {
            Map<String, Object> params = counted(claims.count());
            params.put("amount", claims.amount().toPlainString());
            flag.amber("STOCK_OPEN_CLAIMS", params,
                    plural(claims.count(), "shortage claim") + " of ₹" + claims.amount().toPlainString() + " still open with suppliers");
        }
        return new DaySummary.Stock(flag.orGreen("STOCK_OK", Map.of(), "Every tank is within tolerance and every delivery matched"),
                tanks, deliveries, claims.count(), claims.amount());
    }

    private static DaySummary.Credit credit(DayFigures figures) {
        var aging = figures.credit();
        List<DaySummary.AttentionLine> attention = figures.attentionItems().stream()
                .map(item -> new DaySummary.AttentionLine(item.type(), item.severity(), item.reason(), item.reasonCode(),
                        item.reasonParams()))
                .toList();
        SectionFlag.Builder flag = SectionFlag.builder();
        if (aging.overLimitCount() > 0) {
            flag.red("CREDIT_OVER_LIMIT", counted(aging.overLimitCount()),
                    plural(aging.overLimitCount(), "credit client") + " at or over the credit limit");
        }
        if (aging.overdueCount() > 0) {
            Map<String, Object> params = counted(aging.overdueCount());
            params.put("amount", money(aging.overdueAmount()).toPlainString());
            flag.amber("CREDIT_OVERDUE", params, plural(aging.overdueCount(), "credit client") + " overdue, owing ₹"
                    + money(aging.overdueAmount()).toPlainString());
        }
        if (!attention.isEmpty()) {
            flag.amber("ALERTS_OPEN", counted(attention.size()), plural(attention.size(), "item") + " still needing attention");
        }
        return new DaySummary.Credit(flag.orGreen("CREDIT_OK", Map.of(), "No overdue credit and nothing waiting for attention"),
                sum(figures.closedShifts(), Shift::getCreditTotal), figures.collections(), aging.overdueCount(),
                money(aging.overdueAmount()), aging.overLimitCount(), attention.size(), attention);
    }

    private static List<DaySummary.TankVariance> tankVariances(DayFigures figures) {
        Map<Long, WetStockReconciliationResponse.Period> periodByTank = new LinkedHashMap<>();
        Map<Long, WetStockReconciliationResponse.TankReconciliation> tankById = new LinkedHashMap<>();
        for (WetStockReconciliationResponse.TankReconciliation tank : figures.reconciliation().tanks()) {
            tankById.put(tank.tankId(), tank);
            tank.periods().stream()
                    .filter(period -> period.periodEnd().equals(figures.businessDate()))
                    .reduce((first, second) -> second)
                    .ifPresent(period -> periodByTank.put(tank.tankId(), period));
        }
        return tankById.values().stream()
                .map(tank -> {
                    WetStockReconciliationResponse.Period period = periodByTank.get(tank.tankId());
                    boolean dipped = figures.dippedTankIds().contains(tank.tankId());
                    return period == null
                            ? new DaySummary.TankVariance(tank.tankId(), tank.tankIdentifier(), tank.fuelType(), dipped, null, null, null)
                            : new DaySummary.TankVariance(tank.tankId(), tank.tankIdentifier(), tank.fuelType(), dipped,
                                    period.varianceLitres(), period.variancePercent(), period.withinTolerance());
                })
                .toList();
    }

    private static DaySummary.SafeCountResult safeCountResult(SafeCount count) {
        return new DaySummary.SafeCountResult(count.getCountedAmount(), count.getExpectedAmount(), count.getVarianceAmount(),
                count.getVarianceType(), count.getToleranceAmount(),
                count.getVarianceAmount().abs().compareTo(count.getToleranceAmount()) > 0, count.getCountedAt());
    }

    private static Map<String, Object> safeParams(DaySummary.SafeCountResult safeCount, boolean withTolerance) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("varianceTypeCode", safeCount.varianceType().name());
        params.put("amount", safeCount.varianceAmount().abs().toPlainString());
        if (withTolerance) {
            params.put("toleranceAmount", safeCount.toleranceAmount().toPlainString());
        }
        return params;
    }

    private static Map<String, Object> deliveryParams(DaySummary.DeliveryCheck delivery) {
        return Map.of("invoiceReference", delivery.invoiceReference(), "litres", litresOf(delivery));
    }

    private static String litresOf(DaySummary.DeliveryCheck delivery) {
        return delivery.varianceLitres() == null ? "0" : delivery.varianceLitres().abs().toPlainString();
    }

    private static List<Shift> withDiscrepancy(List<Shift> shifts, DiscrepancyType type) {
        return shifts.stream()
                .filter(shift -> shift.getDiscrepancyType() == type)
                .filter(shift -> shift.getDiscrepancyAmount() != null && shift.getDiscrepancyAmount().signum() > 0)
                .toList();
    }

    private static <T> BigDecimal sum(List<T> items, Function<T, BigDecimal> amountOf) {
        return money(items.stream().map(amountOf).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal money(BigDecimal amount) {
        return (amount != null ? amount : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
