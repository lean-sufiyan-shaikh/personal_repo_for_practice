package com.ppms.shift;

import com.ppms.cash.CashDenominations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** The collections counted before the expected amount is shown; cash is the bag, without confirmed drops. */
public record RecordBlindCountRequest(
        @NotNull(message = "Cash collected is required")
        @DecimalMin(value = "0", message = "Cash collected must be >= 0") BigDecimal cashCollected,
        @Valid CashDenominations cashDenominations,
        @NotNull(message = "UPI collected is required")
        @DecimalMin(value = "0", message = "UPI collected must be >= 0") BigDecimal upiCollected,
        @NotNull(message = "Card collected is required")
        @DecimalMin(value = "0", message = "Card collected must be >= 0") BigDecimal cardCollected,
        @NotNull(message = "Fleet card collected is required")
        @DecimalMin(value = "0", message = "Fleet card collected must be >= 0") BigDecimal fleetCardCollected
) {}
