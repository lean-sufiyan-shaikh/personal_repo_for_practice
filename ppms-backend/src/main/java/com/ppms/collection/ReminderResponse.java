package com.ppms.collection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ReminderResponse(Long id, Long clientId, LocalDate overdueSince, int stageDays, int daysOverdue,
                               BigDecimal outstanding, String channel, ReminderMethod method, String note,
                               String remindedByName, OffsetDateTime remindedAt) {

    static ReminderResponse of(CreditReminder reminder, String remindedByName) {
        return new ReminderResponse(reminder.getId(), reminder.getClientId(), reminder.getOverdueSince(),
                reminder.getStageDays(), reminder.getDaysOverdue(), reminder.getOutstanding(), reminder.getChannel(),
                reminder.getMethod(), reminder.getNote(), remindedByName, reminder.getRemindedAt());
    }
}
