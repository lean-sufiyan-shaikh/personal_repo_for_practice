package com.ppms.dayclose;

import com.ppms.fuel.FuelType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What must be done before a day can be closed (a safe count that day, the next day's fuel prices, every shift of the
 * day closed, and for a past day every expense and credit payment of it decided) and what the owner should know about
 * it (undipped tanks, pending drops, unchecked deliveries, open alerts, today's undecided approvals).
 *
 * Open shifts always block: once the day is closed every write on its shifts is refused, so a shift left open could
 * not be closed without re-opening the day. Undecided expenses and credit payments block a past day, which has had
 * its time to be reviewed, and only warn for today so a manager can close tonight; they then wait for a re-open.
 */
final class DayChecklist {

    private static final String SAFE_PATH = "/dashboard/cash?tab=safe";
    private static final String SAFE_COUNT_PATH = SAFE_PATH + "&action=count-safe&countFor=";
    private static final String PRICES_PATH = "/dashboard/setup?section=prices";
    private static final String SHIFTS_PATH = "/dashboard/shifts";
    private static final String PENDING_EXPENSES_PATH = "/dashboard/approvals?kind=expenses";
    private static final String PENDING_PAYMENTS_PATH = "/dashboard/approvals?kind=payments";

    private DayChecklist() {
    }

    static List<ChecklistItem> checksFor(DayFigures figures) {
        List<ChecklistItem> checks = new ArrayList<>();
        checks.add(new ChecklistItem(ChecklistItem.SAFE_COUNT, true, figures.safeCount().isPresent(), Map.of(),
                figures.safeCount().isPresent()
                        ? "The safe was counted on " + figures.businessDate()
                        : "Count the safe on " + figures.businessDate() + " before closing the day",
                SAFE_COUNT_PATH + figures.businessDate()));
        List<String> missingFuelTypes = figures.fuelTypesWithoutNextDayPrice().stream().map(FuelType::name).toList();
        checks.add(new ChecklistItem(ChecklistItem.NEXT_DAY_PRICES, true, missingFuelTypes.isEmpty(),
                Map.of("fuelTypeCodes", missingFuelTypes),
                missingFuelTypes.isEmpty()
                        ? "The next day's fuel prices are set"
                        : "Set or confirm the next day's price of " + String.join(", ", missingFuelTypes),
                PRICES_PATH));
        int openShifts = figures.openShifts().size();
        checks.add(new ChecklistItem(ChecklistItem.OPEN_SHIFTS, true, openShifts == 0, counted(openShifts),
                openShifts == 0
                        ? "Every shift of " + figures.businessDate() + " is closed"
                        : "Close or hand over " + plural(openShifts, "open shift") + " of " + figures.businessDate()
                                + " first",
                SHIFTS_PATH));
        if (figures.pastDay()) {
            checks.addAll(pendingApprovals(figures, true));
        }
        checks.addAll(warningsFor(figures));
        return checks;
    }

    static List<ChecklistItem> warningsFor(DayFigures figures) {
        List<ChecklistItem> warnings = new ArrayList<>();
        if (!figures.pastDay()) {
            warnings.addAll(pendingApprovals(figures, false));
        }
        int tanksWithoutDip = figures.tanksWithoutDip().size();
        if (tanksWithoutDip > 0) {
            warnings.add(ChecklistItem.warning(ChecklistItem.TANKS_WITHOUT_DIP, counted(tanksWithoutDip),
                    plural(tanksWithoutDip, "active tank") + " with no dip reading this day", "/dashboard/inventory"));
        }
        DayFigures.Total pendingDrops = figures.pendingDrops();
        if (pendingDrops.count() > 0) {
            warnings.add(ChecklistItem.warning(ChecklistItem.PENDING_CASH_DROPS, countedAmount(pendingDrops),
                    plural(pendingDrops.count(), "cash drop") + " of ₹" + pendingDrops.amount().toPlainString()
                            + " waiting to be confirmed in the safe", SAFE_PATH));
        }
        long uncheckedDeliveries = figures.uncheckedDeliveryCount();
        if (uncheckedDeliveries > 0) {
            warnings.add(ChecklistItem.warning(ChecklistItem.DELIVERIES_NOT_CHECKED, counted(uncheckedDeliveries),
                    plural(uncheckedDeliveries, "delivery", "deliveries") + " of this day not checked against the invoice",
                    "/dashboard/inventory"));
        }
        int attentionItems = figures.attentionItems().size();
        if (attentionItems > 0) {
            warnings.add(ChecklistItem.warning(ChecklistItem.OPEN_ATTENTION_ITEMS, counted(attentionItems),
                    plural(attentionItems, "item") + " still needing attention", "/dashboard"));
        }
        return warnings;
    }

    private static List<ChecklistItem> pendingApprovals(DayFigures figures, boolean blocking) {
        List<ChecklistItem> items = new ArrayList<>();
        DayFigures.Total expenses = figures.pendingExpenses();
        if (expenses.count() > 0) {
            items.add(new ChecklistItem(ChecklistItem.PENDING_EXPENSES, blocking, false, countedAmount(expenses),
                    plural(expenses.count(), "expense") + " of ₹" + expenses.amount().toPlainString()
                            + " dated this day waiting for approval", PENDING_EXPENSES_PATH));
        }
        DayFigures.Total payments = figures.pendingCreditPayments();
        if (payments.count() > 0) {
            items.add(new ChecklistItem(ChecklistItem.PENDING_CREDIT_PAYMENTS, blocking, false, countedAmount(payments),
                    plural(payments.count(), "credit payment") + " of ₹" + payments.amount().toPlainString()
                            + " dated this day waiting for approval", PENDING_PAYMENTS_PATH));
        }
        return items;
    }

    private static Map<String, Object> countedAmount(DayFigures.Total total) {
        Map<String, Object> params = counted(total.count());
        params.put("amount", total.amount().toPlainString());
        return params;
    }

    static Map<String, Object> counted(long count) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", count);
        return params;
    }

    static String plural(long count, String noun) {
        return plural(count, noun, noun + "s");
    }

    static String plural(long count, String noun, String nouns) {
        return count + " " + (count == 1 ? noun : nouns);
    }
}
