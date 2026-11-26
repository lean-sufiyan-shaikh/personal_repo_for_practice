package com.ppms.cash;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record BankDepositResponse(
        Long id,
        BigDecimal amount,
        String bankAccount,
        LocalDate depositDate,
        String slipReference,
        String notes,
        boolean hasSlipPhoto,
        String recordedByName,
        OffsetDateTime createdAt
) {}
