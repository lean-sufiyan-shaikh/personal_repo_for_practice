package com.ppms.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money handed over for an advance refund, or for a security deposit received, applied or refunded.
 *
 * @param paymentMode how the money moved; not used when a deposit is applied to the dues. CASH moves it through the safe.
 * @param date        the day a deposit was received; refunds and applications are dated today
 */
public record MoneyMovementRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,
        PaymentMode paymentMode,
        @Size(max = 100, message = "Reference must be at most 100 characters")
        String referenceNo,
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason,
        @PastOrPresent(message = "Date cannot be in the future")
        LocalDate date
) {}
