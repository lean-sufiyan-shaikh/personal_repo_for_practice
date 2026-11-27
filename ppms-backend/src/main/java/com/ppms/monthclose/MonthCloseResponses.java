package com.ppms.monthclose;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Response bodies of the month close endpoints. */
public final class MonthCloseResponses {

    private MonthCloseResponses() {
    }

    /** status, currentVersion and summary are null for a month never closed; summary is the current version's. */
    public record Detail(String month, LocalDate fromDate, LocalDate toDate, boolean monthOver, MonthCloseStatus status,
                         Integer currentVersion, List<Day> days, int notReadyDayCount, MonthSummary summary,
                         List<Version> versions) {}

    /** statusCode is NOT_CLOSED, CLOSED (waiting for approval), REOPENED or APPROVED. */
    public record Day(LocalDate date, String statusCode, String fixPath) {}

    public record Version(int version, String closedByName, OffsetDateTime closedAt, String reopenedByName,
                          OffsetDateTime reopenedAt, String reopenReason) {}

    public record ListItem(String month, MonthCloseStatus status, int currentVersion) {}
}
