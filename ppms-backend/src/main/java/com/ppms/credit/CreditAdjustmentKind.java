package com.ppms.credit;

/** A row that changes what an account owes without being a sale or a payment. */
public enum CreditAdjustmentKind {
    CREDIT_NOTE,
    WRITE_OFF,
    DEPOSIT_APPLIED,
    /** Pays back part of an advance, so the account owes that much more. */
    ADVANCE_REFUND;

    public boolean lowersBalance() {
        return this != ADVANCE_REFUND;
    }
}
