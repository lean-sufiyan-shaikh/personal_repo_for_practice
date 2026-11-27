package com.ppms.collection;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ManualReminderChannelTest {

    private final ManualReminderChannel channel = new ManualReminderChannel();

    @Test
    void carriesEveryWayAPersonRemindsAClient() {
        assertThat(channel.name()).isEqualTo("MANUAL");
        for (ReminderMethod method : ReminderMethod.values()) {
            assertThat(channel.carries(method)).as(method.name()).isTrue();
        }
    }

    @Test
    void deliveringSendsNothingBecauseThePersonAlreadyRemindedTheClient() {
        ReminderMessage message = new ReminderMessage(1L, 2L, "Fleet Co", "9876543210", new BigDecimal("500.00"), 9, null);

        assertThatNoException().isThrownBy(() -> channel.deliver(message, ReminderMethod.CALL));
    }
}
