package com.ppms.collection;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * When an overdue client is due a reminder. An overdue episode runs from the first day a bill is unpaid past its due
 * date until payments clear every past-due bill (a token payment that leaves the oldest bill unpaid does not end it); within it, each stage (days overdue) needs one reminder, and a reminder at a later stage
 * also covers the earlier ones.
 */
public final class ReminderSchedule {

    private ReminderSchedule() {
    }

    /** The latest stage reached at this many days overdue; null before the first. */
    public static Integer reachedStage(Integer[] stageDays, long daysOverdue) {
        return Arrays.stream(stageDays)
                .filter(Objects::nonNull)
                .filter(stage -> stage <= daysOverdue)
                .max(Integer::compareTo)
                .orElse(null);
    }

    /** @param episodeReminders reminders already given in the running episode */
    public static boolean isDue(Integer reachedStage, Collection<CreditReminder> episodeReminders) {
        return reachedStage != null
                && episodeReminders.stream().noneMatch(reminder -> reminder.getStageDays() >= reachedStage);
    }
}
