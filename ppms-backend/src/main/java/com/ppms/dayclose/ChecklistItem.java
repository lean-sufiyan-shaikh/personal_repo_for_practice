package com.ppms.dayclose;

import java.util.Map;

/**
 * One check before a day is closed. A blocking check must be done to close; a warning only tells what the summary
 * will show.
 *
 * @param code    names the check for translated screens, filled with params; text stays the English wording
 * @param fixPath the page where it is put right
 */
public record ChecklistItem(String code, boolean blocking, boolean done, Map<String, Object> params, String text,
                            String fixPath) {

    public static final String SAFE_COUNT = "SAFE_COUNT";
    public static final String NEXT_DAY_PRICES = "NEXT_DAY_PRICES";
    public static final String OPEN_SHIFTS = "OPEN_SHIFTS";
    public static final String TANKS_WITHOUT_DIP = "TANKS_WITHOUT_DIP";
    public static final String PENDING_CASH_DROPS = "PENDING_CASH_DROPS";
    public static final String DELIVERIES_NOT_CHECKED = "DELIVERIES_NOT_CHECKED";
    public static final String OPEN_ATTENTION_ITEMS = "OPEN_ATTENTION_ITEMS";

    static ChecklistItem warning(String code, Map<String, Object> params, String text, String fixPath) {
        return new ChecklistItem(code, false, false, params, text, fixPath);
    }

    boolean isMissingBlockingItem() {
        return blocking && !done;
    }
}
