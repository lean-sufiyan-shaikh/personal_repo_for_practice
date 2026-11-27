package com.ppms.notification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides which page a notification opens, from its type and the shift id or business date kept in its dedup key. */
public final class NotificationTargets {

    private static final Pattern SHIFT_ID = Pattern.compile(":shift:(\\d+)");
    private static final Pattern BUSINESS_DATE = Pattern.compile(":date:(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern CLIENT_ID = Pattern.compile(":client:(\\d+)");

    private NotificationTargets() {
    }

    public static NotificationTarget targetOf(NotificationType type, String dedupKey) {
        return switch (type) {
            case SETTLEMENT_REMINDER -> NotificationTarget.of("/dashboard/settlements");
            case LOW_STOCK -> NotificationTarget.of("/dashboard/inventory");
            case WET_STOCK_LOSS -> NotificationTarget.of("/dashboard/inventory", "tab", "reconciliation");
            case DELIVERY_SHORTAGE -> NotificationTarget.of("/dashboard/inventory", "tab", "claims");
            case CASH_DROP_PENDING, SAFE_COUNT_VARIANCE -> NotificationTarget.of("/dashboard/cash", "tab", "safe");
            case PRICE_STALE -> NotificationTarget.of("/dashboard/setup", "section", "prices");
            case CALIBRATION_DUE, CALIBRATION_DUE_SOON -> NotificationTarget.of("/dashboard/calibration");
            case DOCUMENT_EXPIRING -> NotificationTarget.of("/dashboard/documents");
            case ANCILLARY_LOW_STOCK -> NotificationTarget.of("/dashboard/ancillary");
            case SHIFT_OVERDUE, ZERO_SALE_SHIFT, AUTO_CLOSED_SHIFT, PRICE_CHANGE_OPEN_SHIFT -> shiftTarget(dedupKey);
            case DAY_CLOSED, DAY_REOPENED, DAY_CLOSE_COMMENT -> dayCloseTarget(dedupKey);
            case CREDIT_REMINDERS_DUE, CREDIT_HOLD_PLACED, CREDIT_PROMISE_MISSED ->
                    NotificationTarget.of("/dashboard/credit", "tab", "collections");
            case VEHICLE_CREDIT_FLAGGED -> vehiclesTarget(dedupKey);
            case LEAKAGE_RADAR_WEEKLY -> NotificationTarget.of("/dashboard/radar");
        };
    }

    private static NotificationTarget dayCloseTarget(String dedupKey) {
        Matcher matcher = BUSINESS_DATE.matcher(dedupKey == null ? "" : dedupKey);
        return matcher.find()
                ? NotificationTarget.of("/dashboard/day-close", "date", matcher.group(1))
                : NotificationTarget.of("/dashboard/day-close");
    }

    private static NotificationTarget vehiclesTarget(String dedupKey) {
        Matcher matcher = CLIENT_ID.matcher(dedupKey == null ? "" : dedupKey);
        return matcher.find()
                ? NotificationTarget.of("/dashboard/credit/" + matcher.group(1), "records", "vehicles")
                : NotificationTarget.of("/dashboard/credit");
    }

    private static NotificationTarget shiftTarget(String dedupKey) {
        Matcher matcher = SHIFT_ID.matcher(dedupKey == null ? "" : dedupKey);
        return matcher.find()
                ? NotificationTarget.of("/dashboard/shifts", "shift", matcher.group(1))
                : NotificationTarget.of("/dashboard/shifts");
    }
}
