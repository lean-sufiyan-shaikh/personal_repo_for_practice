package com.ppms.common.time;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Fills in the missing side of an optional from/to date filter with dates Postgres can store. */
public final class DateRangeBounds {

    // Postgres dates cannot hold LocalDate.MIN/MAX.
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    private DateRangeBounds() {
    }

    public static LocalDate startOrEarliest(LocalDate from) {
        return from != null ? from : EARLIEST;
    }

    public static LocalDate endOrLatest(LocalDate to) {
        return to != null ? to : LATEST;
    }

    /** from must not be after to, and the range, both ends counted, must be at most maxDays long. */
    public static void requireRangeOfAtMost(LocalDate from, LocalDate to, int maxDays) {
        requireStartNotAfterEnd(from, to);
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw new BusinessException(ErrorCodes.DATE_RANGE_TOO_LONG, Map.of("maxDays", maxDays),
                    "Choose a date range of at most " + maxDays + " days");
        }
    }

    public static void requireStartNotAfterEnd(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCodes.DATE_RANGE_START_AFTER_END,
                    Map.of(),
                    "'from' date must not be after 'to' date");
        }
    }
}
