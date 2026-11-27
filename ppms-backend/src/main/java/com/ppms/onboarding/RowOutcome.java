package com.ppms.onboarding;

/** What importing a row does: creates a record, skips a record that already exists, or cannot go ahead. */
public enum RowOutcome {
    CREATE,
    SKIP,
    ERROR
}
