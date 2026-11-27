package com.ppms.bank;

/** How the day and month are ordered in a statement's dates; ISO dates (2026-05-04) are read in every mode. */
public enum BankStatementDateFormat {
    /** Worked out from all the file's dates: a first part above 12 means day first, a second part above 12 month first. */
    AUTO,
    /** 04/05/2026 is 4 May. */
    DAY_FIRST,
    /** 04/05/2026 is 5 April. */
    MONTH_FIRST
}
