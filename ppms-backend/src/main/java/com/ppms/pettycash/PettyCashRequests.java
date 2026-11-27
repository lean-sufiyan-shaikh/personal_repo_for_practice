package com.ppms.pettycash;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Request bodies of the petty cash endpoints. */
public final class PettyCashRequests {

    private PettyCashRequests() {
    }

    public record UpdateSettings(
            @NotNull(message = "floatAmount is required")
            @DecimalMin(value = "0.00", message = "floatAmount cannot be negative")
            @DecimalMax(value = "1000000.00", message = "floatAmount must be at most 1000000")
            BigDecimal floatAmount,
            @NotNull(message = "countTolerance is required")
            @DecimalMin(value = "0.00", message = "countTolerance cannot be negative")
            @DecimalMax(value = "10000.00", message = "countTolerance must be at most 10000")
            BigDecimal countTolerance) {}

    public record TopUp(
            @NotNull(message = "amount is required")
            @DecimalMin(value = "0.01", message = "amount must be greater than zero")
            BigDecimal amount,
            LocalDate entryDate,
            @Size(max = 300, message = "notes must be at most 300 characters")
            String notes) {}

    public record Count(
            @NotNull(message = "countedAmount is required")
            @DecimalMin(value = "0.00", message = "countedAmount cannot be negative")
            BigDecimal countedAmount,
            LocalDate countForDate,
            @Size(max = 500, message = "notes must be at most 500 characters")
            String notes) {}
}
