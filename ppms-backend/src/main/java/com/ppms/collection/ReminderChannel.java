package com.ppms.collection;

/**
 * A way a reminder reaches a client. The reminder workflow hands each reminder to the channel that carries its method
 * and records the channel's name, so a channel that sends messages itself (such as WhatsApp) is added as another
 * bean with its own method, without changing the workflow.
 */
public interface ReminderChannel {

    /** Stored with each reminder this channel carried. */
    String name();

    boolean carries(ReminderMethod method);

    /** Throws when the reminder could not be delivered, so nothing is recorded as reminded. */
    void deliver(ReminderMessage message, ReminderMethod method);
}
