package com.ppms.pettycash;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Response bodies of the petty cash endpoints. */
public final class PettyCashResponses {

    private PettyCashResponses() {
    }

    /** suggestedTopUp is what brings the box back to its float; zero when it holds the float or more. */
    public record Summary(BigDecimal floatAmount, BigDecimal countTolerance, BigDecimal balance,
                          BigDecimal suggestedTopUp, Count latestCount) {}

    public record Entry(Long id, PettyCashEntryType entryType, BigDecimal amount, BigDecimal balanceAfter,
                        String description, LocalDate entryDate, Long expenseId, boolean reversed,
                        OffsetDateTime createdAt) {}

    public record Count(Long id, LocalDate countForDate, BigDecimal countedAmount, BigDecimal expectedAmount,
                        BigDecimal varianceAmount, BigDecimal toleranceAmount, boolean beyondTolerance, String notes,
                        String countedByName, OffsetDateTime countedAt) {}
}
