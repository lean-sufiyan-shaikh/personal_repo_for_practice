package com.ppms.pump;

import java.util.List;

/** How far a pump's setup has got: each step with its state, and how many of the required steps are done. */
public record SetupChecklistResponse(Long pumpId, int doneCount, int requiredCount, List<Item> items) {

    public enum Step {
        DISPENSERS,
        TANKS,
        FUEL_PRICES,
        SHIFT_DEFINITIONS,
        STAFF,
        GST_DETAILS,
        CREDIT_CLIENTS
    }

    /** One step; {@code detail} says what is set up or what is still missing. */
    public record Item(Step step, boolean done, boolean optional, String detail) {
    }

    static SetupChecklistResponse of(Long pumpId, List<Item> items) {
        int required = (int) items.stream().filter(item -> !item.optional()).count();
        int done = (int) items.stream().filter(item -> !item.optional() && item.done()).count();
        return new SetupChecklistResponse(pumpId, done, required, items);
    }
}
