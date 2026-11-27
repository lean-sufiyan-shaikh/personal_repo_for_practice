package com.ppms.audit;

/**
 * All audit actions tracked by the system.
 * Values must exactly match the audit_action ENUM in the DB (V1__complete_schema.sql).
 * Adding a new value here requires a corresponding DB migration to add it to the ENUM type.
 */
public enum AuditAction {
    // ── User management ──────────────────────────────────────────────────────
    USER_CREATED,
    USER_UPDATED,
    USER_DEACTIVATED,
    USER_STATUS_CHANGED,
    PASSWORD_RESET,
    USER_PUMP_CHANGED,

    // ── Authentication ────────────────────────────────────────────────────────
    LOGIN,
    LOGIN_FAILED,
    TOKEN_REVOKED,

    // ── Fuel pricing ──────────────────────────────────────────────────────────
    FUEL_PRICE_UPDATED,

    // ── Shift lifecycle ───────────────────────────────────────────────────────
    SHIFT_OPENED,
    SHIFT_CLOSED,
    SHIFT_BACKFILLED,
    SHIFT_AMENDED,
    DISCREPANCY_RESOLVED,
    DISCREPANCY_INVESTIGATION_STARTED,

    // ── Credit management ─────────────────────────────────────────────────────
    CREDIT_ENTRY_VOIDED,
    CREDIT_LIMIT_CHANGED,
    CREDIT_PAYMENT_RECEIVED,
    INTEREST_APPLIED,
    INTEREST_CHARGE_WAIVED,
    CREDIT_PAYMENT_REVERSED,
    INTEREST_SETTINGS_CHANGED,
    CREDIT_CLIENT_CREATED,
    CREDIT_CLIENT_DELETED,
    CREDIT_ENTRY_REASSIGNED,
    CREDIT_INVOICE_ISSUED,
    CREDIT_INVOICE_CANCELLED,
    CREDIT_EXTENSION_GRANTED,
    CREDIT_EXTENSION_REVOKED,
    INTEREST_RECALCULATED,

    // ── Inventory ─────────────────────────────────────────────────────────────
    DELIVERY_RECORDED,
    DELIVERY_UPDATED,
    LOT_COST_CORRECTED,
    DELIVERY_DELETED,
    DIP_CHECK_RECORDED,
    SHORTAGE_CLAIM_RAISED,
    SHORTAGE_CLAIM_UPDATED,
    SHORTAGE_CLAIM_SETTLED,
    SHORTAGE_CLAIM_REJECTED,
    SHORTAGE_CLAIM_WITHDRAWN,

    // ── Documents ─────────────────────────────────────────────────────────────
    DOCUMENT_ADDED,

    // ── Expenses ──────────────────────────────────────────────────────────────
    EXPENSE_RECORDED,
    EXPENSE_APPROVED,
    EXPENSE_REJECTED,

    // ── Payroll ───────────────────────────────────────────────────────────────
    PAYROLL_APPROVED,
    PAYROLL_DELETED,
    PAYROLL_PAID,
    PAYROLL_RETURNED_TO_DRAFT,

    // ── Pump management ───────────────────────────────────────────────────────
    PUMP_CREATED,
    PUMP_DELETED,
    PUMP_UPDATED,
    PUMP_SUSPENDED,
    PUMP_ENABLED,
    OWNER_ONBOARDED,
    NOZZLE_ADDED,
    CALIBRATION_LOGGED,
    TANK_ADDED,

    // ── Ancillary products ────────────────────────────────────────────────────
    ANCILLARY_SALE_RECORDED,
    ANCILLARY_PRICE_OVERRIDDEN,
    ANCILLARY_SALE_BACKFILLED,
    ANCILLARY_DELIVERY_RECORDED,
    ANCILLARY_DELIVERY_BACKFILLED,

    // ── Operations ────────────────────────────────────────────────────────────
    HANDOVER_COMPLETED,
    PUMP_CLOSURE_ADDED,
    BANK_STATEMENT_IMPORTED,
    BANK_LINE_MATCHED,
    BANK_LINE_UNMATCHED,
    BANK_LINE_IGNORED,
    BANK_LINES_AUTO_MATCHED,
    SUPPLIER_PAYMENT_RECORDED,
    SUPPLIER_PAYMENT_VOIDED,
    SUPPLIER_OPENING_BALANCE_SET,
    SUPPLIER_DEFAULT_SET,
    TEST_LITRES_ABOVE_MAX,
    PUMP_CONFIG_UPDATED,

    // ── Payment settlements ───────────────────────────────────────────────────
    SETTLEMENT_RECORDED,
    SETTLEMENT_VOIDED,
    SETTLEMENT_CONFIG_UPDATED,

    // ── Cash custody ──────────────────────────────────────────────────────────
    CASH_DROP_RECORDED,
    CASH_DROP_CONFIRMED,
    CASH_DROP_REJECTED,
    CASH_EVENT_RECORDED,
    CASH_EVENT_REVERSED,
    SAFE_COUNTED,
    SAFE_COUNTED_LATE,
    SAFE_COUNT_BACK_DATED,
    BANK_DEPOSIT_RECORDED,
    DEPOSIT_SLIP_ATTACHED,

    // ── Day close ─────────────────────────────────────────────────────────────
    DAY_CLOSED,
    DAY_APPROVED,
    DAY_REOPENED,
    DAY_CLOSE_COMMENTED,

    // ── Credit collections ────────────────────────────────────────────────────
    CREDIT_REMINDER_RECORDED,
    CREDIT_PROMISE_RECORDED,
    CREDIT_PROMISE_EDITED,
    CREDIT_PROMISE_CANCELLED,
    COLLECTION_SETTINGS_UPDATED,
    CLIENT_VEHICLE_SAVED,
    CLIENT_VEHICLE_STATUS_CHANGED,
    VEHICLE_CHECK_CHANGED,

    // ── Onboarding ────────────────────────────────────────────────────────────
    STAFF_IMPORTED,
    CREDIT_CLIENTS_IMPORTED,
    PRODUCTS_IMPORTED,
    TANK_STOCK_IMPORTED,
    DEMO_PUMP_CREATED,
    DEMO_PUMP_DELETED,

    // ── Credit controls ───────────────────────────────────────────────────────
    CREDIT_CLIENT_UPDATED,
    CREDIT_CLIENT_STATUS_CHANGED,
    CREDIT_PORTAL_LOCKED,
    CREDIT_CLOSE_BLOCKED_ALLOWED,

    // ── Meter adjustments ─────────────────────────────────────────────────────
    METER_READING_ADJUSTED,
    METER_ADJUSTMENT_REQUESTED,
    METER_ADJUSTMENT_APPROVED,
    METER_ADJUSTMENT_REJECTED
}
