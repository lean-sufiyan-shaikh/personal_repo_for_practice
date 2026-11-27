package com.ppms.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VoidStaffAdvanceRequest(
        @NotBlank(message = "Void reason is required")
        @Size(max = 500, message = "Void reason must be at most 500 characters") String voidReason
) {}
