package com.ppms.notification;

public enum NotificationType {
    LOW_STOCK,
    PRICE_STALE,
    DOCUMENT_EXPIRING,
    CALIBRATION_DUE,
    /** Fired once per nozzle and due date when the next calibration falls due within 7 days. */
    CALIBRATION_DUE_SOON,
    SHIFT_OVERDUE,
    /** Fired when a shift closes with ₹0 in sales but was open for more than 30 minutes. */
    ZERO_SALE_SHIFT,
    /** Fired when a fuel price is updated while at least one shift is currently open. */
    PRICE_CHANGE_OPEN_SHIFT,
    /** Fired when an ancillary product's stock falls at or below its configured low_stock_threshold. */
    ANCILLARY_LOW_STOCK,
    /**
     * Fired when the OverdueShiftJob force-closes a shift that was never submitted by the operator.
     * Signals to management that meter readings are missing and a manual reconciliation is required.
     */
    AUTO_CLOSED_SHIFT,
    /**
     * Fired daily after the configured alert time (per pump, per payment type) to remind
     * Admin/Owner to record the day's UPI / Card / Fleet Card bank settlement.
     * Dedup key format: SETTLEMENT_REMINDER:{type}:{pumpId}:{date}
     */
    SETTLEMENT_REMINDER,
    /**
     * Fired once a day per pump and payment type while collections older than T+2 are still not in the bank.
     * Dedup key format: SETTLEMENT_OVERDUE:{type}:{pumpId}:{date}
     */
    SETTLEMENT_OVERDUE,
    /** Fired once per tank and day while the tank's 7-day reconciled loss is above its loss alert limit. */
    WET_STOCK_LOSS,
    /** Fired once per delivery whose receipt check found it short beyond the pump's tolerance. */
    DELIVERY_SHORTAGE,
    /** Fired once per cash drop waiting for a manager to confirm it reached the safe. */
    CASH_DROP_PENDING,
    /** Fired once per safe count that differs from the ledger by more than the pump's tolerance. */
    SAFE_COUNT_VARIANCE,
    /** Fired once per close of a business day, for the owner to approve it. */
    DAY_CLOSED,
    /** Fired once per re-open of a closed business day, with the reason. */
    DAY_REOPENED,
    /** Fired once per question or reply on a closed business day. */
    DAY_CLOSE_COMMENT,
    /** Fired once a day while overdue credit clients reached a reminder stage nobody has reminded them of. */
    CREDIT_REMINDERS_DUE,
    /** Fired once per overdue episode when a client's credit goes on hold. */
    CREDIT_HOLD_PLACED,
    /** Fired once per promise to pay that passed its date without the promised amount paid. */
    CREDIT_PROMISE_MISSED,
    /** Fired once per credit payment an Owner or Admin reversed, such as a bounced cheque. */
    CREDIT_PAYMENT_REVERSED,
    /** Fired once a day per account when a WARN account's credit entry is let through outside its vehicle rules. */
    VEHICLE_CREDIT_FLAGGED,
    /** Fired once a week, for owners and admins only, when the leakage radar has something worth a look. */
    LEAKAGE_RADAR_WEEKLY,
    /** Fired once per lockout of the credit balance page after too many wrong PINs, for owners and admins only. */
    CREDIT_PORTAL_LOCKED,
    /** Fired once per change of a credit client's phone, which turns off its balance-page PIN; owners and admins only. */
    CREDIT_CLIENT_PHONE_CHANGED,
    /** Fired once per petty cash count whose difference from the petty cash ledger is beyond the box's tolerance. */
    PETTY_CASH_COUNT_VARIANCE
}
