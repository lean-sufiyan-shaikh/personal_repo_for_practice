package com.ppms.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GiveStaffAdvanceRequest(
        @NotNull(message = "Staff member is required") Long staffUserId,
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero") BigDecimal amount,
        @NotNull(message = "Advance date is required") LocalDate advanceDate,
        @Size(max = 500, message = "Note must be at most 500 characters") String note
) {}
