package com.ppms.cash;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** denominations is optional; when sent it must add up to countedAmount. */
public record RecordSafeCountRequest(
        @NotNull(message = "Counted amount is required")
        @DecimalMin(value = "0", message = "Counted amount cannot be negative")
        @DecimalMax(value = "9999999999.99", message = "Counted amount is too large")
        BigDecimal countedAmount,
        @Valid CashDenominations denominations,
        @Size(max = 500, message = "Notes must be at most 500 characters") String notes
) {}
