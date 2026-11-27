package com.ppms.dayclose;

/** A CLOSED or APPROVED day refuses writes dated on it; a REOPENED day takes them again until it is closed again. */
public enum DayCloseStatus {
    CLOSED,
    REOPENED,
    APPROVED;

    public boolean isLocked() {
        return this != REOPENED;
    }
}
