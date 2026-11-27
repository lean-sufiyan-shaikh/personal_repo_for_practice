package com.ppms.settlement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Linked to a settlement (its payment type is then taken from it) or to the collection day the payment was taken. */
public record RecordChargebackRequest(
        @NotNull(message = "kind is required")
        ChargebackKind kind,
        SettlementPaymentType paymentType,
        @NotNull(message = "chargebackDate is required")
        LocalDate chargebackDate,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,
        @Size(max = 100, message = "reference must be at most 100 characters")
        String reference,
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason,
        Long settlementId,
        LocalDate collectionDate
) {}
