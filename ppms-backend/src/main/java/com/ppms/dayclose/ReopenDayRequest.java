package com.ppms.dayclose;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReopenDayRequest(
        @NotBlank(message = "A reason is required to re-open a closed day")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
