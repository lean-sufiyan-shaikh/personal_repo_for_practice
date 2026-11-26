package com.ppms.inventory;

import com.ppms.common.dto.PagedResponse;

import java.math.BigDecimal;

/** One page of claims; the totals cover every claim of the pump whatever the status filter. */
public record ShortageClaimListResponse(PagedResponse<ShortageClaimResponse> claims, Totals totals) {

    /** settledAmount is the sum of the credit notes received. */
    public record Totals(long openCount, BigDecimal openAmount, long settledCount, BigDecimal settledAmount,
                         long rejectedCount) {
    }
}
