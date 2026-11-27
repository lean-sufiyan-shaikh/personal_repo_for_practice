package com.ppms.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param resolution   ACCEPTED or CREDITED
 * @param creditAmount for CREDITED, how much to credit; null credits all of the disputed amount
 */
public record ResolveDisputeRequest(
        @NotNull(message = "A resolution is required") CreditDisputeStatus resolution,
        @NotBlank(message = "A note is required")
        @Size(max = 500, message = "Note must be at most 500 characters")
        String note,
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal creditAmount
) {}
