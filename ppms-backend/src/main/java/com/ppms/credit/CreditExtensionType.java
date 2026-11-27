package com.ppms.credit;

/**
 * Type of temporary credit override granted by Admin or Owner (spec Section 3.6, Business Rule 51).
 *
 * AMOUNT_EXTENSION      — allows the customer additional credit headroom beyond their limit.
 * BILLING_CYCLE_EXTENSION — defers the overdue block deadline by N days.
 * OVERDUE_BLOCK_WAIVER  — one-time waiver of the overdue block; highest risk, requires justification.
 * CREDIT_HOLD_OVERRIDE  — lifts the credit hold placed on a client long overdue, and the overdue block with it.
 */
public enum CreditExtensionType {
    AMOUNT_EXTENSION,
    BILLING_CYCLE_EXTENSION,
    OVERDUE_BLOCK_WAIVER,
    CREDIT_HOLD_OVERRIDE
}
