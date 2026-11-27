package com.ppms.collection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** The person marking the reminder already called, met or messaged the client, so there is nothing left to send. */
@Slf4j
@Component
public class ManualReminderChannel implements ReminderChannel {

    private static final Set<ReminderMethod> METHODS =
            EnumSet.of(ReminderMethod.CALL, ReminderMethod.IN_PERSON, ReminderMethod.MESSAGE_COPIED);

    @Override
    public String name() {
        return "MANUAL";
    }

    @Override
    public boolean carries(ReminderMethod method) {
        return METHODS.contains(method);
    }

    @Override
    public void deliver(ReminderMessage message, ReminderMethod method) {
        log.info("Manual credit reminder given: pump={} client={} method={} daysOverdue={}",
                message.pumpId(), message.clientId(), method, message.daysOverdue());
    }
}
