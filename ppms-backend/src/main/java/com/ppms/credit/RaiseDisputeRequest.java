package com.ppms.credit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RaiseDisputeRequest(
        @NotNull(message = "The kind of debit is required") CreditDebitType debitType,
        @NotNull(message = "The debit is required") Long debitId,
        @NotBlank(message = "A reason is required")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
