package com.ppms.credit;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param bounceCharge optional charge added to the client's account as a separate debit, e.g. the bank's cheque
 *                     bounce charge
 */
public record ReversePaymentRequest(
        @NotBlank(message = "A reason is required to reverse a payment")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason,
        @DecimalMin(value = "0.01", message = "Bounce charge must be greater than zero")
        @DecimalMax(value = "100000.00", message = "Bounce charge must be at most ₹1,00,000")
        @Digits(integer = 6, fraction = 2, message = "Bounce charge can have at most 2 decimal places")
        BigDecimal bounceCharge
) {}
