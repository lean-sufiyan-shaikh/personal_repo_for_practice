package com.ppms.collection;

public enum PromiseStatus {
    OPEN,
    /** The client paid at least the promised amount after the promise was recorded. */
    KEPT,
    /** The date passed without the promised amount paid. */
    MISSED,
    /** Withdrawn before it was kept or missed. */
    CANCELLED
}
