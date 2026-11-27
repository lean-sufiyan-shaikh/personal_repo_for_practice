package com.ppms.credit;

public enum CreditDisputeStatus {
    OPEN,
    /** The debit stands and is owed like any other, interest included. */
    ACCEPTED,
    /** The debit was credited with a credit note. */
    CREDITED
}
