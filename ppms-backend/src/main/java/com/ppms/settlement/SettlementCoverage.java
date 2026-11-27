package com.ppms.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The collection days a settlement clears when it is applied oldest day first, and whether the last day it
 * reaches is left partly unsettled.
 */
record SettlementCoverage(LocalDate fromDate, LocalDate toDate, boolean partial) {

    /**
     * @param unsettledDays unsettled collection days of the settlement's payment type, oldest first
     * @param grossAmount   the gross the settlement clears
     */
    static SettlementCoverage of(List<PendingSettlementRange.Day> unsettledDays, BigDecimal grossAmount) {
        BigDecimal remaining = grossAmount;
        LocalDate from = null;
        LocalDate to = null;
        boolean partial = false;
        for (PendingSettlementRange.Day day : unsettledDays) {
            if (remaining.signum() <= 0) {
                break;
            }
            if (from == null) {
                from = day.date();
            }
            to = day.date();
            BigDecimal applied = day.amount().min(remaining);
            partial = applied.compareTo(day.amount()) < 0;
            remaining = remaining.subtract(applied);
        }
        return new SettlementCoverage(from, to, partial);
    }
}
