package com.ppms.dayclose;

import com.ppms.attention.AttentionItem;
import com.ppms.attention.AttentionSeverity;
import com.ppms.attention.AttentionType;
import com.ppms.cash.SafeCount;
import com.ppms.credit.CreditAgingResponse;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.DeliveryCheckStatus;
import com.ppms.inventory.TankerDelivery;
import com.ppms.inventory.WetStockReconciliationResponse;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.report.DaySalesReader;
import com.ppms.shift.DiscrepancyType;
import com.ppms.shift.Shift;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The flag of each summary section and the reasons given for it. */
class DaySummaryBuilderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 26);
    private static final OffsetDateTime BUILT_AT = OffsetDateTime.of(2026, 11, 27, 9, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

    @Test
    void quietDayIsGreenInEverySectionWithTheFiguresAddedUp() {
        DaySummary summary = DaySummaryBuilder.build(figures().build(), BUILT_AT);

        assertThat(summary.overallFlag()).isEqualTo(FlagColour.GREEN);
        assertThat(codes(summary.sales().flag())).containsExactly("SALES_OK");
        assertThat(codes(summary.cash().flag())).containsExactly("CASH_OK");
        assertThat(codes(summary.stock().flag())).containsExactly("STOCK_OK");
        assertThat(codes(summary.credit().flag())).containsExactly("CREDIT_OK");
        assertThat(summary.sales().fuelLitres()).isEqualByComparingTo("100.000");
        assertThat(summary.sales().fuelRevenue()).isEqualByComparingTo("10000.00");
        assertThat(summary.sales().fuelMargin()).isEqualByComparingTo("1000.00");
        assertThat(summary.sales().productMargin()).isEqualByComparingTo("50.00");
        assertThat(summary.sales().grossMargin()).isEqualByComparingTo("1050.00");
        assertThat(summary.cash().cash()).isEqualByComparingTo("8000.00");
        assertThat(summary.cash().upi()).isEqualByComparingTo("2000.00");
        assertThat(summary.cash().safeCount().beyondTolerance()).isFalse();
        assertThat(summary.warnings()).isEmpty();
    }

    @Test
    void salesAreRedBelowCostAndAmberForOpenShiftsMissingCostAndNoClosedShift() {
        DayFigures belowCost = figures()
                .sales(new DaySalesReader.DaySales(List.of(fuel(FuelType.PETROL, "100", "9000", "9500")), noProducts()))
                .openShifts(List.of(shift(null, null)))
                .build();
        SectionFlag flag = DaySummaryBuilder.build(belowCost, BUILT_AT).sales().flag();
        assertThat(flag.colour()).isEqualTo(FlagColour.RED);
        assertThat(codes(flag)).containsExactly("SALES_NEGATIVE_MARGIN", "SALES_OPEN_SHIFTS_EXCLUDED");
        assertThat(flag.reasons().get(0).params()).containsEntry("amount", "500.00");

        DayFigures noCost = figures()
                .closedShifts(List.of())
                .sales(new DaySalesReader.DaySales(List.of(fuel(FuelType.DIESEL, "50", "4500", "0")), noProducts()))
                .build();
        SectionFlag amber = DaySummaryBuilder.build(noCost, BUILT_AT).sales().flag();
        assertThat(amber.colour()).isEqualTo(FlagColour.AMBER);
        assertThat(codes(amber)).containsExactly("SALES_NO_CLOSED_SHIFTS", "SALES_COST_MISSING");
        assertThat(amber.reasons().get(1).params()).containsEntry("fuelTypeCode", "DIESEL");
    }

    @Test
    void cashIsRedForASafeCountBeyondToleranceAndAmberForShortShiftsAndPendingDrops() {
        DayFigures figures = figures()
                .closedShifts(List.of(shift(DiscrepancyType.SHORT, "120.00"), shift(DiscrepancyType.OVER, "30.00")))
                .safeCount(Optional.of(safeCount("950.00", "1000.00")))
                .pendingDrops(new DayFigures.Total(2, new BigDecimal("700.00")))
                .build();

        DaySummary.Cash cash = DaySummaryBuilder.build(figures, BUILT_AT).cash();
        assertThat(cash.flag().colour()).isEqualTo(FlagColour.RED);
        assertThat(codes(cash.flag())).containsExactly("CASH_SAFE_BEYOND_TOLERANCE", "CASH_SHIFT_SHORTAGES", "CASH_PENDING_DROPS");
        assertThat(cash.flag().reasons().get(0).params())
                .containsEntry("varianceTypeCode", "SHORT").containsEntry("amount", "50.00").containsEntry("toleranceAmount", "10.00");
        assertThat(cash.shortageAmount()).isEqualByComparingTo("120.00");
        assertThat(cash.overAmount()).isEqualByComparingTo("30.00");
        assertThat(cash.shortShiftCount()).isEqualTo(1);
        assertThat(cash.safeCount().beyondTolerance()).isTrue();

        DaySummary.Cash small = DaySummaryBuilder.build(figures().safeCount(Optional.of(safeCount("995.00", "1000.00"))).build(),
                BUILT_AT).cash();
        assertThat(small.flag().colour()).isEqualTo(FlagColour.AMBER);
        assertThat(codes(small.flag())).containsExactly("CASH_SAFE_SMALL_VARIANCE");

        DaySummary.Cash uncounted = DaySummaryBuilder.build(figures().safeCount(Optional.empty()).build(), BUILT_AT).cash();
        assertThat(codes(uncounted.flag())).containsExactly("CASH_NO_SAFE_COUNT");
        assertThat(uncounted.safeCount()).isNull();
    }

    @Test
    void stockIsRedForATankOutsideToleranceOrAShortDeliveryAndAmberForTheRest() {
        UndergroundTank undipped = tank(2L, "T-2");
        DayFigures figures = figures()
                .reconciliation(reconciliation(period(DAY, "-80.000", false)))
                .deliveries(List.of(delivery(1L, "INV-1", DeliveryCheckStatus.SHORT, "-40.000"),
                        delivery(2L, "INV-2", DeliveryCheckStatus.OVER, "15.000"),
                        delivery(3L, "INV-3", DeliveryCheckStatus.NOT_CHECKED, null)))
                .tanksToDip(List.of(tank(1L, "T-1"), undipped))
                .openClaims(new DayFigures.Total(1, new BigDecimal("3600.00")))
                .build();

        DaySummary summary = DaySummaryBuilder.build(figures, BUILT_AT);
        SectionFlag flag = summary.stock().flag();
        assertThat(flag.colour()).isEqualTo(FlagColour.RED);
        assertThat(codes(flag)).containsExactly("STOCK_TANK_OUTSIDE_TOLERANCE", "STOCK_DELIVERY_SHORT", "STOCK_DELIVERY_OVER",
                "STOCK_TANKS_NOT_DIPPED", "STOCK_DELIVERIES_NOT_CHECKED", "STOCK_OPEN_CLAIMS");
        assertThat(flag.reasons().get(0).params()).containsEntry("tankIdentifier", "T-1").containsEntry("litres", "-80.000");
        assertThat(flag.reasons().get(1).params()).containsEntry("invoiceReference", "INV-1").containsEntry("litres", "40.000");
        assertThat(summary.stock().tanks()).singleElement().satisfies(tank -> {
            assertThat(tank.dipped()).isTrue();
            assertThat(tank.withinTolerance()).isFalse();
        });
        assertThat(summary.stock().deliveries()).extracting(DaySummary.DeliveryCheck::invoiceReference)
                .containsExactly("INV-1", "INV-2", "INV-3");
        assertThat(summary.warnings()).extracting(ChecklistItem::code)
                .containsExactly(ChecklistItem.TANKS_WITHOUT_DIP, ChecklistItem.DELIVERIES_NOT_CHECKED);
    }

    @Test
    void creditIsRedForOverLimitClientsAndAmberForOverdueClientsAndOpenAlerts() {
        DayFigures figures = figures()
                .credit(new CreditAgingResponse.Summary(new BigDecimal("50000"), new BigDecimal("12000.5"), BigDecimal.ZERO, 3, 1, 0))
                .attentionItems(List.of(new AttentionItem(AttentionType.EXPIRING_DOCUMENTS, AttentionSeverity.HIGH, 1L, "Pump",
                        1, null, "1 document expired", "ATTENTION_EXPIRED_DOCUMENTS", Map.of("count", 1), "/dashboard/documents")))
                .build();

        DaySummary.Credit credit = DaySummaryBuilder.build(figures, BUILT_AT).credit();
        assertThat(credit.flag().colour()).isEqualTo(FlagColour.RED);
        assertThat(codes(credit.flag())).containsExactly("CREDIT_OVER_LIMIT", "CREDIT_OVERDUE", "ALERTS_OPEN");
        assertThat(credit.flag().reasons().get(1).params()).containsEntry("count", 3L).containsEntry("amount", "12000.50");
        assertThat(credit.overdueAmount()).isEqualByComparingTo("12000.50");
        assertThat(credit.openAttentionCount()).isEqualTo(1);
        assertThat(credit.creditGiven()).isEqualByComparingTo("500.00");
        assertThat(credit.collections()).isEqualByComparingTo("300.00");
    }

    @Test
    void openShiftsAlwaysBlockTheCloseAndAreNotAWarning() {
        DayFigures figures = figures().openShifts(List.of(shift(null, null), shift(null, null))).build();

        assertThat(DayChecklist.checksFor(figures)).filteredOn(ChecklistItem::isMissingBlockingItem)
                .singleElement().satisfies(check -> {
                    assertThat(check.code()).isEqualTo(ChecklistItem.OPEN_SHIFTS);
                    assertThat(check.params()).containsEntry("count", 2L);
                    assertThat(check.fixPath()).isEqualTo("/dashboard/shifts");
                });
        assertThat(DayChecklist.warningsFor(figures)).isEmpty();
        assertThat(DayChecklist.checksFor(figures().build())).filteredOn(check -> check.code().equals(ChecklistItem.OPEN_SHIFTS))
                .singleElement().satisfies(check -> assertThat(check.done()).isTrue());
    }

    @Test
    void pendingExpensesAndCreditPaymentsBlockAPastDayAndOnlyWarnForToday() {
        FiguresBuilder withPending = figures()
                .pendingExpenses(new DayFigures.Total(2, new BigDecimal("1500.00")))
                .pendingCreditPayments(new DayFigures.Total(1, new BigDecimal("800.00")));

        List<ChecklistItem> pastDay = DayChecklist.checksFor(withPending.pastDay(true).build());
        assertThat(pastDay).filteredOn(ChecklistItem::isMissingBlockingItem).extracting(ChecklistItem::code)
                .containsExactly(ChecklistItem.PENDING_EXPENSES, ChecklistItem.PENDING_CREDIT_PAYMENTS);
        assertThat(pastDay).filteredOn(check -> check.code().equals(ChecklistItem.PENDING_EXPENSES)).singleElement()
                .satisfies(check -> {
                    assertThat(check.params()).containsEntry("count", 2L).containsEntry("amount", "1500.00");
                    assertThat(check.fixPath()).isEqualTo("/dashboard/approvals?kind=expenses");
                });

        DaySummary today = DaySummaryBuilder.build(withPending.pastDay(false).build(), BUILT_AT);
        assertThat(today.warnings()).extracting(ChecklistItem::code)
                .containsExactly(ChecklistItem.PENDING_EXPENSES, ChecklistItem.PENDING_CREDIT_PAYMENTS);
        assertThat(today.warnings()).noneMatch(ChecklistItem::blocking);
        assertThat(DayChecklist.checksFor(withPending.build())).noneMatch(ChecklistItem::isMissingBlockingItem);
    }

    @Test
    void pettyCashInUseButNotCountedThatDayIsAWarningThatNeverBlocks() {
        List<ChecklistItem> uncounted = DayChecklist.checksFor(figures().pettyCash(true, false).build());
        assertThat(uncounted).filteredOn(check -> check.code().equals(ChecklistItem.PETTY_CASH_NOT_COUNTED))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.blocking()).isFalse();
                    assertThat(check.fixPath()).isEqualTo("/dashboard/cash?tab=petty&action=count-petty&countFor=" + DAY);
                });

        assertThat(DayChecklist.checksFor(figures().pettyCash(true, true).build()))
                .noneMatch(check -> check.code().equals(ChecklistItem.PETTY_CASH_NOT_COUNTED));
        assertThat(DayChecklist.checksFor(figures().pettyCash(false, false).build()))
                .noneMatch(check -> check.code().equals(ChecklistItem.PETTY_CASH_NOT_COUNTED));
    }

    @Test
    void checklistBlocksOnAMissingSafeCountAndMissingNextDayPrices() {
        List<ChecklistItem> checks = DayChecklist.checksFor(figures()
                .safeCount(Optional.empty())
                .fuelTypesWithoutNextDayPrice(List.of(FuelType.DIESEL))
                .build());

        assertThat(checks).filteredOn(ChecklistItem::isMissingBlockingItem).extracting(ChecklistItem::code)
                .containsExactly(ChecklistItem.SAFE_COUNT, ChecklistItem.NEXT_DAY_PRICES);
        assertThat(checks.get(1).params()).containsEntry("fuelTypeCodes", List.of("DIESEL"));
        assertThat(checks.get(0).fixPath()).isEqualTo("/dashboard/cash?tab=safe&action=count-safe&countFor=" + DAY);
        assertThat(checks.get(1).fixPath()).isEqualTo("/dashboard/setup?section=prices");
    }

    /** A summary with nothing in it, for tests that only need a stored snapshot. */
    static DaySummary emptyDaySummary(LocalDate day) {
        return DaySummaryBuilder.build(figures()
                .businessDate(day)
                .closedShifts(List.of())
                .sales(new DaySalesReader.DaySales(List.of(), noProducts()))
                .build(), BUILT_AT);
    }

    private static List<String> codes(SectionFlag flag) {
        return flag.reasons().stream().map(SectionFlag.Reason::code).toList();
    }

    private static FiguresBuilder figures() {
        return new FiguresBuilder();
    }

    private static DaySalesReader.FuelLine fuel(FuelType fuelType, String litres, String revenue, String cost) {
        return new DaySalesReader.FuelLine(fuelType, new BigDecimal(litres).setScale(3), new BigDecimal(revenue).setScale(2),
                new BigDecimal(cost).setScale(2));
    }

    private static DaySalesReader.ProductTotals noProducts() {
        return new DaySalesReader.ProductTotals(new BigDecimal("0.00"), new BigDecimal("0.00"));
    }

    private static Shift shift(DiscrepancyType discrepancyType, String discrepancy) {
        return Shift.builder()
                .cashCollected(new BigDecimal("4000.00"))
                .upiCollected(new BigDecimal("1000.00"))
                .cardCollected(BigDecimal.ZERO)
                .fleetCardCollected(BigDecimal.ZERO)
                .creditTotal(new BigDecimal("250.00"))
                .discrepancyType(discrepancyType)
                .discrepancyAmount(discrepancy == null ? null : new BigDecimal(discrepancy))
                .build();
    }

    private static SafeCount safeCount(String counted, String expected) {
        BigDecimal variance = new BigDecimal(counted).subtract(new BigDecimal(expected));
        return SafeCount.builder()
                .countedAmount(new BigDecimal(counted))
                .expectedAmount(new BigDecimal(expected))
                .varianceAmount(variance)
                .varianceType(variance.signum() < 0 ? DiscrepancyType.SHORT : variance.signum() > 0 ? DiscrepancyType.OVER : null)
                .toleranceAmount(new BigDecimal("10.00"))
                .countedAt(BUILT_AT.minusHours(12))
                .build();
    }

    private static UndergroundTank tank(Long id, String identifier) {
        return UndergroundTank.builder().id(id).tankIdentifier(identifier).fuelType(FuelType.PETROL)
                .status(TankStatus.ACTIVE).currentStock(new BigDecimal("1000")).build();
    }

    private static TankerDelivery delivery(Long id, String invoice, DeliveryCheckStatus status, String variance) {
        return TankerDelivery.builder().id(id).invoiceReference(invoice).fuelType(FuelType.PETROL)
                .quantityDelivered(new BigDecimal("4000.000"))
                .receivedLitres(variance == null ? null : new BigDecimal("4000.000").add(new BigDecimal(variance)))
                .varianceLitres(variance == null ? null : new BigDecimal(variance))
                .checkStatus(status)
                .deliveryDate(BUILT_AT.minusDays(1).plusMinutes(id))
                .build();
    }

    private static WetStockReconciliationResponse.Period period(LocalDate end, String variance, boolean within) {
        return new WetStockReconciliationResponse.Period(end, end, 1, WetStockReconciliationResponse.PeriodStatus.RECONCILED,
                new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("4000"),
                new BigDecimal("4000").add(new BigDecimal(variance)), new BigDecimal(variance), new BigDecimal("-8.00"), within,
                new BigDecimal(variance), new BigDecimal("-8.00"), null);
    }

    private static WetStockReconciliationResponse reconciliation(WetStockReconciliationResponse.Period... periods) {
        return new WetStockReconciliationResponse(DAY, DAY, List.of(new WetStockReconciliationResponse.TankReconciliation(
                1L, "T-1", "PETROL", new BigDecimal("50"), new BigDecimal("0.5"), null, List.of(periods))));
    }

    /** A quiet day: two balanced shifts, a matching safe count, every tank dipped, nothing open. */
    private static final class FiguresBuilder {
        private LocalDate businessDate = DAY;
        private List<Shift> closedShifts = List.of(shift(null, null), shift(null, null));
        private List<Shift> openShifts = List.of();
        private Optional<SafeCount> safeCount = Optional.of(DaySummaryBuilderTest.safeCount("1000.00", "1000.00"));
        private List<FuelType> fuelTypesWithoutNextDayPrice = List.of();
        private DayFigures.Total pendingDrops = new DayFigures.Total(0, new BigDecimal("0.00"));
        private List<TankerDelivery> deliveries = List.of();
        private DayFigures.Total openClaims = new DayFigures.Total(0, new BigDecimal("0.00"));
        private List<UndergroundTank> tanksToDip = List.of(tank(1L, "T-1"));
        private WetStockReconciliationResponse reconciliation = DaySummaryBuilderTest.reconciliation(period(DAY, "-5.000", true));
        private DaySalesReader.DaySales sales = new DaySalesReader.DaySales(
                List.of(fuel(FuelType.PETROL, "100", "10000", "9000")),
                new DaySalesReader.ProductTotals(new BigDecimal("150.00"), new BigDecimal("100.00")));
        private CreditAgingResponse.Summary credit = new CreditAgingResponse.Summary(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, 0);
        private List<AttentionItem> attentionItems = List.of();
        private boolean pastDay = false;
        private DayFigures.Total pendingExpenses = new DayFigures.Total(0, new BigDecimal("0.00"));
        private DayFigures.Total pendingCreditPayments = new DayFigures.Total(0, new BigDecimal("0.00"));
        private boolean pettyCashInUse;
        private boolean pettyCashCounted;

        FiguresBuilder pastDay(boolean value) { pastDay = value; return this; }
        FiguresBuilder pettyCash(boolean inUse, boolean counted) { pettyCashInUse = inUse; pettyCashCounted = counted; return this; }
        FiguresBuilder pendingExpenses(DayFigures.Total value) { pendingExpenses = value; return this; }
        FiguresBuilder pendingCreditPayments(DayFigures.Total value) { pendingCreditPayments = value; return this; }
        FiguresBuilder businessDate(LocalDate value) { businessDate = value; return this; }
        FiguresBuilder closedShifts(List<Shift> value) { closedShifts = value; return this; }
        FiguresBuilder openShifts(List<Shift> value) { openShifts = value; return this; }
        FiguresBuilder safeCount(Optional<SafeCount> value) { safeCount = value; return this; }
        FiguresBuilder fuelTypesWithoutNextDayPrice(List<FuelType> value) { fuelTypesWithoutNextDayPrice = value; return this; }
        FiguresBuilder pendingDrops(DayFigures.Total value) { pendingDrops = value; return this; }
        FiguresBuilder deliveries(List<TankerDelivery> value) { deliveries = value; return this; }
        FiguresBuilder openClaims(DayFigures.Total value) { openClaims = value; return this; }
        FiguresBuilder tanksToDip(List<UndergroundTank> value) { tanksToDip = value; return this; }
        FiguresBuilder reconciliation(WetStockReconciliationResponse value) { reconciliation = value; return this; }
        FiguresBuilder sales(DaySalesReader.DaySales value) { sales = value; return this; }
        FiguresBuilder credit(CreditAgingResponse.Summary value) { credit = value; return this; }
        FiguresBuilder attentionItems(List<AttentionItem> value) { attentionItems = value; return this; }

        DayFigures build() {
            return new DayFigures(businessDate, closedShifts, openShifts, safeCount, fuelTypesWithoutNextDayPrice, pendingDrops,
                    new DayFigures.Total(1, new BigDecimal("700.00")), new DayFigures.Total(1, new BigDecimal("5000.00")),
                    deliveries, openClaims, tanksToDip, Set.of(1L), reconciliation, sales, new BigDecimal("300.00"), credit,
                    attentionItems, pastDay, pendingExpenses, pendingCreditPayments, pettyCashInUse, pettyCashCounted);
        }
    }
}
