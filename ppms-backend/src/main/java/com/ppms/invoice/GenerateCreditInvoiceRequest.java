package com.ppms.invoice;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** @param coverSubAccounts a parent's invoice also bills its sub-accounts; null takes the pump's default */
public record GenerateCreditInvoiceRequest(
        @NotNull(message = "clientId is required") Long clientId,
        @NotNull(message = "periodFrom is required") LocalDate periodFrom,
        @NotNull(message = "periodTo is required") LocalDate periodTo,
        Boolean coverSubAccounts
) {}
