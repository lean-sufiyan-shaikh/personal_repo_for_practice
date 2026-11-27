package com.ppms.bank;

/** Kinds of record a bank statement line can be matched to. */
public enum BankMatchRecordType {
    /** A UPI, card or fleet card settlement; a credit line for its net amount. */
    SETTLEMENT,
    /** Cash deposited from the safe; a credit line. */
    DEPOSIT,
    /** A payment to a fuel supplier; a debit line. */
    SUPPLIER_PAYMENT,
    /** A credit customer's payment; a credit line. */
    CREDIT_PAYMENT,
    SHIFT,
    ANCILLARY_SALE
}
