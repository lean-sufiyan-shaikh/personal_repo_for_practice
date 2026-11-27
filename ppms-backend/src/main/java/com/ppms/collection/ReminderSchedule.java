package com.ppms.collection;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * When an overdue client is due a reminder. An overdue episode runs from the first overdue day until a payment makes
 * the client current again; within it, each stage (days overdue) needs one reminder, and a reminder at a later stage
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

    /** First overdue day of the running episode; stays the same day after day until a payment ends the episode. */
    public static LocalDate overdueSince(LocalDate today, long daysOverdue) {
        return today.minusDays(daysOverdue - 1);
    }

    /** @param episodeReminders reminders already given in the running episode */
    public static boolean isDue(Integer reachedStage, Collection<CreditReminder> episodeReminders) {
        return reachedStage != null
                && episodeReminders.stream().noneMatch(reminder -> reminder.getStageDays() >= reachedStage);
    }
}
