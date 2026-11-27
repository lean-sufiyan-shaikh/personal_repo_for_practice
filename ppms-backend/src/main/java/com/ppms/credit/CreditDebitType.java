package com.ppms.credit;

/** A debit on a credit account that a credit note or a dispute can name; the values match the ledger row types. */
public enum CreditDebitType {
    SALE,
    COUNTER_SALE,
    INTEREST,
    BOUNCE_CHARGE
}
