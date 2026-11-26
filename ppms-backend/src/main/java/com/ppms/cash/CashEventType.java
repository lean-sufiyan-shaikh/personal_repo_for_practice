package com.ppms.cash;

public enum CashEventType {
    OPENING_BALANCE,
    CASH_IN,
    CASH_OUT,
    CLOSING_BALANCE,
    /** A confirmed cash drop from an open shift; only CashDropService writes it. */
    CASH_DROP,
    /** Cash taken to the bank; only the bank deposit flow writes it, with its slip reference. */
    BANK_DEPOSIT;

    public boolean isRecordedByHand() {
        return this != CASH_DROP && this != BANK_DEPOSIT;
    }
}
