package com.ppms.cash;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** denominations is optional; when sent it must add up to amount. */
public record RecordCashDropRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @DecimalMax(value = "9999999999.99", message = "Amount is too large")
        BigDecimal amount,
        @Valid CashDenominations denominations
) {}
