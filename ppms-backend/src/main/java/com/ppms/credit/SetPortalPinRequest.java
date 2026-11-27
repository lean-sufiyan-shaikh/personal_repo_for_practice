package com.ppms.credit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SetPortalPinRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "PIN must be 6 digits")
        String pin) {
}
