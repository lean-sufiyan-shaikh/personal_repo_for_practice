package com.ppms.cash;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * The safe right now: its ledger balance, drops still waiting to be confirmed, and the latest count (or null).
 * yesterdayCountOpen says whether it is still before countCutoffTime, so a manager may count the safe for yesterday.
 */
public record SafeSummaryResponse(
        BigDecimal balance,
        long pendingDropCount,
        BigDecimal pendingDropAmount,
        BigDecimal countTolerance,
        SafeCountResponse latestCount,
        LocalTime countCutoffTime,
        boolean yesterdayCountOpen
) {}
