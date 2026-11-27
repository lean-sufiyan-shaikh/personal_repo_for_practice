package com.ppms.expense;

/** Where the money for an expense came from. SAFE and PETTY_CASH move cash once the expense is approved. */
public enum ExpensePaidFrom {
    SAFE,
    PETTY_CASH,
    BANK,
    OWNER_POCKET
}
