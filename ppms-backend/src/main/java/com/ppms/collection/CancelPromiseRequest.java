package com.ppms.collection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelPromiseRequest(
        @NotBlank(message = "A reason is required to cancel a promise")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
