package com.ppms.cash;

import com.ppms.shift.DiscrepancyType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** varianceAmount is counted minus expected; beyondTolerance says whether the owner was notified. */
public record SafeCountResponse(
        Long id,
        BigDecimal countedAmount,
        BigDecimal expectedAmount,
        BigDecimal varianceAmount,
        DiscrepancyType varianceType,
        BigDecimal toleranceAmount,
        boolean beyondTolerance,
        CashDenominations denominations,
        String notes,
        String countedByName,
        OffsetDateTime countedAt
) {}
