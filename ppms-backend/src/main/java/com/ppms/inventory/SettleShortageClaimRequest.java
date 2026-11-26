package com.ppms.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettleShortageClaimRequest(
        @NotNull(message = "Credit note amount is required")
        @DecimalMin(value = "0.01", message = "Credit note amount must be greater than 0")
        BigDecimal creditNoteAmount,

        @NotBlank(message = "Credit note reference is required")
        @Size(max = 100, message = "Credit note reference must be at most 100 characters")
        String creditNoteReference,

        @NotNull(message = "Credit note date is required")
        LocalDate creditNoteDate
) {}
