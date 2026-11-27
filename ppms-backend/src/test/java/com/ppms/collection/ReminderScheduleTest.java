package com.ppms.collection;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderScheduleTest {

    private static final Integer[] STAGES = {7, 15, 30};
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void reachedStageIsTheLatestStageAtOrBelowTheDaysOverdue() {
        assertThat(ReminderSchedule.reachedStage(STAGES, 6)).isNull();
        assertThat(ReminderSchedule.reachedStage(STAGES, 7)).isEqualTo(7);
        assertThat(ReminderSchedule.reachedStage(STAGES, 14)).isEqualTo(7);
        assertThat(ReminderSchedule.reachedStage(STAGES, 15)).isEqualTo(15);
        assertThat(ReminderSchedule.reachedStage(STAGES, 44)).isEqualTo(30);
        assertThat(ReminderSchedule.reachedStage(new Integer[0], 44)).isNull();
    }

    @Test
    void eachStageIsDueOnceAndAReminderAtAStageCoversTheEarlierOnes() {
        CreditReminder atSeven = reminderAt(7);
        CreditReminder atFifteen = reminderAt(15);

        assertThat(ReminderSchedule.isDue(7, List.of())).isTrue();
        assertThat(ReminderSchedule.isDue(7, List.of(atSeven))).isFalse();
        assertThat(ReminderSchedule.isDue(15, List.of(atSeven))).isTrue();
        assertThat(ReminderSchedule.isDue(15, List.of(atSeven, atFifteen))).isFalse();
        assertThat(ReminderSchedule.isDue(7, List.of(atFifteen))).isFalse();
        assertThat(ReminderSchedule.isDue(30, List.of(atSeven, atFifteen))).isTrue();
    }

    @Test
    void nothingIsDueBeforeTheFirstStage() {
        assertThat(ReminderSchedule.isDue(null, List.of())).isFalse();
    }

    private static CreditReminder reminderAt(int stage) {
        return CreditReminder.builder().stageDays(stage).overdueSince(TODAY).build();
    }
}
