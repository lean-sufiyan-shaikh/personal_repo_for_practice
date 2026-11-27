package com.ppms.dayclose;

import java.time.LocalDate;
import java.util.List;

/**
 * @param status   null when the day was never closed
 * @param canClose the day is not in the future, not already closed, and every blocking check is done
 * @param checks   the blocking checks, done or not, then the warnings that apply
 */
public record DayClosePreviewResponse(LocalDate businessDate, DayCloseStatus status, boolean canClose,
                                      List<ChecklistItem> checks, DaySummary summary) {
}
