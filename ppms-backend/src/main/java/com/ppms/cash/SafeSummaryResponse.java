package com.ppms.cash;

import java.math.BigDecimal;

/** The safe right now: its ledger balance, drops still waiting to be confirmed, and the latest count (or null). */
public record SafeSummaryResponse(
        BigDecimal balance,
        long pendingDropCount,
        BigDecimal pendingDropAmount,
        BigDecimal countTolerance,
        SafeCountResponse latestCount
) {}
