package com.ppms.shift;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request body for PATCH /api/shifts/{id}/discrepancy-resolution.
 * Used by Manager or Admin to set the resolution action after a discrepant shift is closed.
 * WAIVED (spec Business Rule 19) and PENDING_INVESTIGATION require a non-blank resolutionNote. A CASH_RECOVERY of
 * part of the shortage leaves the shift pending for the rest.
 */
public record ResolveDiscrepancyRequest(

        @NotNull(message = "Resolution action is required")
        DiscrepancyResolution resolutionAction,

        String resolutionNote,

        /** CASH_RECOVERY only: the cash handed back, at most the shortage; absent means all of it. */
        BigDecimal recoveredAmount
) {}
