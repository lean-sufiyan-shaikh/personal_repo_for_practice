package com.ppms.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param debitType        with debitId, the sale or charge being credited; both null credit the account's oldest bills
 * @param returnedQuantity litres or units taken back, for a credited sale
 */
public record IssueCreditNoteRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,
        @NotBlank(message = "A reason is required")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason,
        CreditDebitType debitType,
        Long debitId,
        @DecimalMin(value = "0.001", message = "Returned quantity must be greater than zero")
        @Digits(integer = 9, fraction = 3, message = "Returned quantity can have at most 3 decimal places")
        BigDecimal returnedQuantity
) {}
