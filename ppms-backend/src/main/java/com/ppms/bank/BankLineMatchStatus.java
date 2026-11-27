package com.ppms.bank;

public enum BankLineMatchStatus {
    /** Not yet matched to any system transaction. Default state on import. */
    UNMATCHED,
    /** Linked automatically by amount and date to a likely record; a person accepts or changes it. */
    SUGGESTED,
    /** Linked to a settlement, deposit, supplier payment, credit payment, shift or ancillary sale. */
    MATCHED,
    /** Manually marked as not relevant by Owner/Admin (fees, internal transfers, etc.), with a reason. */
    IGNORED
}
