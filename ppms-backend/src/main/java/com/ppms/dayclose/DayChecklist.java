package com.ppms.dayclose;

import com.ppms.fuel.FuelType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What must be done before a day can be closed (a safe count that day, the next day's fuel prices) and what the owner
 * should know about it (open shifts, undipped tanks, pending drops, unchecked deliveries, open alerts).
 */
final class DayChecklist {

    private static final String SAFE_PATH = "/dashboard/cash?tab=safe";
    private static final String PRICES_PATH = "/dashboard/setup?section=prices";

    private DayChecklist() {
    }

    static List<ChecklistItem> checksFor(DayFigures figures) {
        List<ChecklistItem> checks = new ArrayList<>();
        checks.add(new ChecklistItem(ChecklistItem.SAFE_COUNT, true, figures.safeCount().isPresent(), Map.of(),
                figures.safeCount().isPresent()
                        ? "The safe was counted on " + figures.businessDate()
                        : "Count the safe on " + figures.businessDate() + " before closing the day",
                SAFE_PATH));
        List<String> missingFuelTypes = figures.fuelTypesWithoutNextDayPrice().stream().map(FuelType::name).toList();
        checks.add(new ChecklistItem(ChecklistItem.NEXT_DAY_PRICES, true, missingFuelTypes.isEmpty(),
                Map.of("fuelTypeCodes", missingFuelTypes),
                missingFuelTypes.isEmpty()
                        ? "The next day's fuel prices are set"
                        : "Set or confirm the next day's price of " + String.join(", ", missingFuelTypes),
                PRICES_PATH));
        checks.addAll(warningsFor(figures));
        return checks;
    }

    static List<ChecklistItem> warningsFor(DayFigures figures) {
        List<ChecklistItem> warnings = new ArrayList<>();
        int openShifts = figures.openShifts().size();
        if (openShifts > 0) {
            warnings.add(ChecklistItem.warning(ChecklistItem.OPEN_SHIFTS, counted(openShifts),
                    plural(openShifts, "shift") + " of this day still open; they are left out of the day's figures",
                    "/dashboard/shifts"));
        }
        int tanksWithoutDip = figures.tanksWithoutDip().size();
        if (tanksWithoutDip > 0) {
            warnings.add(ChecklistItem.warning(ChecklistItem.TANKS_WITHOUT_DIP, counted(tanksWithoutDip),
                    plural(tanksWithoutDip, "active tank") + " with no dip reading this day", "/dashboard/inventory"));
        }
        DayFigures.Total pendingDrops = figures.pendingDrops();
        if (pendingDrops.count() > 0) {
            Map<String, Object> params = counted(pendingDrops.count());
            params.put("amount", pendingDrops.amount().toPlainString());
            warnings.add(ChecklistItem.warning(ChecklistItem.PENDING_CASH_DROPS, params,
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
