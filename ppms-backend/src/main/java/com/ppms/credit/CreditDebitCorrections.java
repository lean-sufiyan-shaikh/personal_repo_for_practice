package com.ppms.credit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The credit notes and open disputes of a set of clients, read once so the overdue standing and the interest
 * principal treat each debit the same way: a debit counts at what is left of it after the credit notes naming it,
 * and an openly disputed debit is left out of what is chased and charged interest.
 *
 * @param adjustments every adjustment row of the clients
 */
public record CreditDebitCorrections(List<CreditAdjustment> adjustments,
                                     Map<String, BigDecimal> creditedByDebit,
                                     Set<String> openlyDisputedDebits) {

    public static String keyOf(CreditDebitType type, Long debitId) {
        return type.name() + ":" + debitId;
    }

    public BigDecimal remainingOf(CreditDebitType type, Long debitId, BigDecimal amount) {
        return amount.subtract(creditedByDebit.getOrDefault(keyOf(type, debitId), BigDecimal.ZERO));
    }

    public boolean isDisputed(CreditDebitType type, Long debitId) {
        return openlyDisputedDebits.contains(keyOf(type, debitId));
    }

    /** Adjustments that name no debit: they clear the oldest bills first, like a payment. */
    public List<CreditAdjustment> accountWideAdjustments() {
        return adjustments.stream().filter(adjustment -> !adjustment.creditsDebit()).toList();
    }
}
