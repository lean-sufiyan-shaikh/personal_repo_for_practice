package com.ppms.cash;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CashDropResponse(
        Long id,
        Long shiftId,
        Long operatorId,
        String operatorName,
        BigDecimal amount,
        CashDenominations denominations,
        CashDropStatus status,
        String recordedByName,
        OffsetDateTime recordedAt,
        String reviewedByName,
        OffsetDateTime reviewedAt,
        String rejectionReason
) {}
