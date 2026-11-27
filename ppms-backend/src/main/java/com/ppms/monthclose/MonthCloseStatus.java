package com.ppms.monthclose;

/** A CLOSED month refuses writes and day re-opens on its days; a REOPENED month takes them again. */
public enum MonthCloseStatus {
    CLOSED,
    REOPENED
}
