package com.ppms.cash;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordBankDepositRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @DecimalMax(value = "9999999999.99", message = "Amount is too large")
        BigDecimal amount,
        @NotBlank(message = "Bank account is required")
        @Size(max = 100, message = "Bank account must be at most 100 characters")
        String bankAccount,
        @NotNull(message = "Deposit date is required")
        LocalDate depositDate,
        @NotBlank(message = "Slip reference is required")
        @Size(max = 100, message = "Slip reference must be at most 100 characters")
        String slipReference,
        @Size(max = 500, message = "Notes must be at most 500 characters")
        String notes
) {}
