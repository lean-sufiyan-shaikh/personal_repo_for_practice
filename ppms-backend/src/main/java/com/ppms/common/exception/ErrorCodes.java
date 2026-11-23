package com.ppms.common.exception;

/**
 * Codes sent as ErrorResponse.code; the frontend shows locales/&lt;lang&gt;/errors.json["&lt;CODE&gt;"] filled with the params.
 * A param whose name ends in "Code" holds an enum code that the frontend translates too.
 */
public final class ErrorCodes {

    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String TOO_MANY_LOGIN_ATTEMPTS = "TOO_MANY_LOGIN_ATTEMPTS";
    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";
    public static final String CURRENT_PASSWORD_INCORRECT = "CURRENT_PASSWORD_INCORRECT";
    public static final String NEW_PASSWORD_SAME_AS_CURRENT = "NEW_PASSWORD_SAME_AS_CURRENT";
    public static final String NOZZLE_SHIFT_ALREADY_OPEN = "NOZZLE_SHIFT_ALREADY_OPEN";
    public static final String OPERATOR_SHIFT_ALREADY_OPEN = "OPERATOR_SHIFT_ALREADY_OPEN";
    public static final String OPERATOR_INACTIVE = "OPERATOR_INACTIVE";
    public static final String CLOSING_READING_BELOW_OPENING = "CLOSING_READING_BELOW_OPENING";
    public static final String CREDIT_ENTRIES_TOTAL_MISMATCH = "CREDIT_ENTRIES_TOTAL_MISMATCH";
    public static final String CREDIT_CLIENT_DISABLED = "CREDIT_CLIENT_DISABLED";
    public static final String CREDIT_LIMIT_EXCEEDED = "CREDIT_LIMIT_EXCEEDED";
    public static final String CREDIT_BILLING_CYCLE_OVERDUE = "CREDIT_BILLING_CYCLE_OVERDUE";
    public static final String INSUFFICIENT_FUEL_STOCK = "INSUFFICIENT_FUEL_STOCK";

    private ErrorCodes() {
    }
}
