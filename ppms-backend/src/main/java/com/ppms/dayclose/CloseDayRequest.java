package com.ppms.dayclose;

/** approve is for an Owner or Admin closing the day and approving it in the same step. */
public record CloseDayRequest(Boolean approve) {

    boolean approveNow() {
        return Boolean.TRUE.equals(approve);
    }
}
