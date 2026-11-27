package com.ppms.credit;

/** What waiving an interest charge means for the period it covered. */
public enum InterestWaiverKind {
    /** The period still counts as charged, so the next interest run does not charge it again. */
    KEEP_PERIOD_CHARGED,
    /** The period is left uncharged, so the next interest run charges it again from the balance on record. */
    RECALCULATE
}
