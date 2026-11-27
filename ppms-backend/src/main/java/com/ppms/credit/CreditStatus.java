package com.ppms.credit;

/** Whether a client can take a credit sale right now, by the same rules the sale itself is checked against. */
public enum CreditStatus {
    OK,
    OVERDUE,
    /** Overdue for at least the pump's credit hold days; only a credit hold override lifts it. */
    ON_HOLD,
    OVER_LIMIT,
    /** At or above the pump's near-limit share of the effective limit, and otherwise OK. */
    NEAR_LIMIT,
    INACTIVE
}
