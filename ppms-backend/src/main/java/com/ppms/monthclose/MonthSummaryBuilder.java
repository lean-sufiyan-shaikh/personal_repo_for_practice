package com.ppms.monthclose;

import com.ppms.cash.CashEventRepository;
import com.ppms.credit.CreditAgingService;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.inventory.WetStockReconciliationResponse;
import com.ppms.inventory.WetStockReconciliationService;
import com.ppms.pettycash.PettyCashLedger;
import com.ppms.report.DaySalesReader;
import com.ppms.report.ProfitLossReport;
import com.ppms.report.ProfitLossReportService;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Reads a month's figures into its summary: one pass over its business days plus the month's profit and loss. */
@Component
@RequiredArgsConstructor
class MonthSummaryBuilder {

    private final ShiftRepository shiftRepository;
    private final DaySalesReader daySalesReader;
    private final ProfitLossReportService profitLossReportService;
    private final CreditPaymentRepository creditPaymentRepository;
    private final CreditAgingService creditAgingService;
    private final CashEventRepository cashEventRepository;
    private final PettyCashLedger pettyCashLedger;
    private final WetStockReconciliationService reconciliationService;

    MonthSummary build(Long pumpId, YearMonth month, List<LocalDate> businessDays, int approvedDayCount,
                       List<MonthSummary.ExceptionDay> exceptions, OffsetDateTime builtAt) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        Map<LocalDate, List<Long>> closedShiftIdsByDay = shiftRepository.findClosedShiftsByDateRange(pumpId, from, to).stream()
                .collect(Collectors.groupingBy(Shift::getShiftDate, Collectors.mapping(Shift::getId, Collectors.toList())));

        Map<String, MonthSummary.FuelLine> fuelsByType = new TreeMap<>();
        BigDecimal productRevenue = BigDecimal.ZERO;
        BigDecimal productCost = BigDecimal.ZERO;
        for (LocalDate day : businessDays) {
            DaySalesReader.DaySales sales = daySalesReader.salesOf(pumpId, day, closedShiftIdsByDay.getOrDefault(day, List.of()));
            sales.fuels().forEach(line -> fuelsByType.merge(line.fuelType().name(),
                    new MonthSummary.FuelLine(line.fuelType().name(), line.litres(), line.revenue(), line.cost(),
                            line.revenue().subtract(line.cost())), MonthSummaryBuilder::add));
            productRevenue = productRevenue.add(sales.products().revenue());
            productCost = productCost.add(sales.products().cost());
        }
        List<MonthSummary.FuelLine> fuels = List.copyOf(fuelsByType.values());
        BigDecimal fuelRevenue = money(fuels.stream().map(MonthSummary.FuelLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal fuelCost = money(fuels.stream().map(MonthSummary.FuelLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal fuelMargin = fuelRevenue.subtract(fuelCost);
        BigDecimal productMargin = money(productRevenue.subtract(productCost));
        BigDecimal grossProfit = fuelMargin.add(productMargin);

        ProfitLossReport profitLoss = profitLossReportService.buildProfitLossReport(pumpId, from, to);
        MonthSummary.Costs costs = costsOf(profitLoss);
        return new MonthSummary(month.toString(), from, to, builtAt, businessDays.size(), approvedDayCount, exceptions,
                fuels,
                fuels.stream().map(MonthSummary.FuelLine::litres).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(3, RoundingMode.HALF_UP),
                fuelRevenue, fuelCost, fuelMargin, money(productRevenue), money(productCost), productMargin,
                paymentsOf(profitLoss), grossProfit, costs, grossProfit.subtract(costs.total()),
                new MonthSummary.Credit(profitLoss.totalCreditRevenue(),
                        money(creditPaymentRepository.sumApprovedByPumpIdBetween(pumpId, from, to)),
                        money(creditAgingService.agingOfPump(pumpId).summary().totalOutstanding())),
                money(cashEventRepository.computeBalanceAtEndOf(pumpId, to)),
                pettyCashLedger.balanceAtEndOf(pumpId, to),
                tanksOf(reconciliationService.reconcile(pumpId, from, to, null)));
    }

    /** Whatever else the profit and loss deducts beyond the costs named here is kept as otherCosts. */
    private static MonthSummary.Costs costsOf(ProfitLossReport report) {
        BigDecimal named = report.approvedExpenses().add(report.payrollPaid()).add(report.bankCharges())
                .add(report.chargebacks()).add(report.badDebtWrittenOff());
        BigDecimal other = report.grossProfit().subtract(named).subtract(report.netOperatingProfit());
        return new MonthSummary.Costs(report.approvedExpenses(), report.payrollPaid(), report.bankCharges(),
                report.chargebacks(), report.badDebtWrittenOff(), money(other), money(named.add(other)));
    }

    private static MonthSummary.Payments paymentsOf(ProfitLossReport report) {
        return new MonthSummary.Payments(report.totalCashRevenue(), report.totalUpiRevenue(), report.totalCardRevenue(),
                report.totalFleetCardRevenue(), report.totalCreditRevenue(), report.totalCollected());
    }

    private static List<MonthSummary.TankClosing> tanksOf(WetStockReconciliationResponse reconciliation) {
        return reconciliation.tanks().stream()
                .map(tank -> {
                    WetStockReconciliationResponse.Period lastDip = tank.periods().stream()
                            .filter(period -> period.closingLitres() != null)
                            .max(Comparator.comparing(WetStockReconciliationResponse.Period::periodEnd))
                            .orElse(null);
                    return new MonthSummary.TankClosing(tank.tankId(), tank.tankIdentifier(), tank.fuelType(),
                            lastDip != null ? lastDip.periodEnd() : null,
                            lastDip != null ? lastDip.closingLitres() : null,
                            tank.summary().varianceLitres());
                })
                .toList();
    }

    private static MonthSummary.FuelLine add(MonthSummary.FuelLine first, MonthSummary.FuelLine second) {
        return new MonthSummary.FuelLine(first.fuelType(), first.litres().add(second.litres()),
                first.revenue().add(second.revenue()), first.cost().add(second.cost()),
                first.margin().add(second.margin()));
    }

    private static BigDecimal money(BigDecimal amount) {
        return Objects.requireNonNullElse(amount, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
