package com.ppms.dayclose;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param balanceSheetStale the DAY balance sheet generated at close changed afterwards
 * @param summary           the snapshot of the current version
 * @param versions          every close of the day, newest first
 * @param comments          questions, replies and re-open reasons, oldest first
 */
public record DayCloseDetailResponse(LocalDate businessDate, DayCloseStatus status, int version, String closedByName,
                                     OffsetDateTime closedAt, String approvedByName, OffsetDateTime approvedAt,
                                     Long balanceSheetId, boolean balanceSheetStale, DaySummary summary,
                                     List<VersionItem> versions, List<CommentItem> comments) {

    public record VersionItem(int version, String closedByName, OffsetDateTime closedAt) {}

    public record CommentItem(Long id, DayCloseCommentKind kind, String message, String authorName,
                              OffsetDateTime createdAt) {}
}
