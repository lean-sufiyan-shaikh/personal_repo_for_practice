package com.ppms.cash;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseCashEventRequest(
        @NotBlank(message = "Reason is required")
        @Size(max = 400, message = "Reason must be at most 400 characters")
        String reason
) {}
