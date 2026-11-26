package com.ppms.cash;

import com.ppms.common.dto.PagedResponse;

import java.math.BigDecimal;

/** One page of deposits plus the count and total of every deposit matching the filters. */
public record BankDepositListResponse(
        PagedResponse<BankDepositResponse> deposits,
        long totalCount,
        BigDecimal totalAmount
) {}
