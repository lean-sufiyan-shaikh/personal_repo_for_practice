package com.ppms.cash;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One money movement of the safe; amount is signed (negative for money going out). */
public record SafeLedgerEntryResponse(
        Long id,
        CashEventType eventType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String description,
        LocalDate eventDate,
        OffsetDateTime createdAt
) {}
