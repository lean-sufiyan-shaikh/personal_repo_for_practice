package com.ppms.collection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An overdue account that reached a reminder stage nobody has reminded it of in this episode. Covers the whole group
 * of a parent account.
 *
 * @param stageDays          the stage the reminder is for
 * @param lastRemindedAt     the latest reminder of this episode, at an earlier stage; null when none
 * @param upiPayLink         null when the pump has no UPI details
 */
public record ReminderDueResponse(Long clientId, String clientName, String phone, BigDecimal outstanding,
                                  long daysOverdue, LocalDate overdueSince, int stageDays, boolean onHold,
                                  OffsetDateTime lastRemindedAt, ReminderMethod lastReminderMethod, String upiPayLink) {
}
