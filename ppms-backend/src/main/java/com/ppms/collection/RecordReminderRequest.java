package com.ppms.collection;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecordReminderRequest(
        @NotNull(message = "Choose how the client was reminded") ReminderMethod method,
        @Size(max = 500, message = "Note must be at most 500 characters") String note
) {}
