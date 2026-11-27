package com.ppms.cash;

import com.ppms.shift.DiscrepancyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * varianceAmount is counted minus expected; beyondTolerance says whether the owner was notified. late is set when the
 * count was recorded after countForDate had ended; backDateReason only on a count back-dated after the morning cut-off.
 */
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
        OffsetDateTime countedAt,
        LocalDate countForDate,
        boolean late,
        String backDateReason
) {}
