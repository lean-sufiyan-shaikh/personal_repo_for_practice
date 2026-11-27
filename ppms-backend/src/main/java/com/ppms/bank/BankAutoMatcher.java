package com.ppms.bank;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pairs statement lines with records, each record with one line at most.
 *
 * A line whose UTR or narration carries exactly one record's reference (settlement bank reference, deposit slip,
 * payment reference) is MATCHED when the amounts agree within ₹1, otherwise SUGGESTED. Every other line is
 * SUGGESTED the record of the same amount within ₹1 dated 0 to 3 days before it, the closest date first, since an
 * amount and a date alone can belong to more than one record.
 */
final class BankAutoMatcher {

    static final BigDecimal AMOUNT_TOLERANCE = BigDecimal.ONE;
    static final int MAX_DAYS_AFTER_RECORD = 3;
    private static final int MIN_REFERENCE_LENGTH = 4;

    record Decision(MatchCandidate candidate, BankLineMatchStatus status) {
    }

    private BankAutoMatcher() {
    }

    /**
     * @param lines      lines to place, oldest first
     * @param candidates records not yet held by another line, both credit-side and debit-side
     * @return decision per line id, for lines that found a record
     */
    static Map<Long, Decision> match(List<BankStatementLine> lines, List<MatchCandidate> candidates) {
        Map<Long, Decision> decisions = new LinkedHashMap<>();
        Set<BankMatchRef> taken = new HashSet<>();

        for (BankStatementLine line : lines) {
            List<MatchCandidate> byReference = candidates.stream()
                    .filter(candidate -> fitsSide(line, candidate) && referenceMatches(line, candidate.reference()))
                    .toList();
            if (byReference.size() == 1 && !taken.contains(byReference.getFirst().ref())) {
                MatchCandidate candidate = byReference.getFirst();
                BankLineMatchStatus status = amountMatches(line, candidate)
                        ? BankLineMatchStatus.MATCHED : BankLineMatchStatus.SUGGESTED;
                decisions.put(line.getId(), new Decision(candidate, status));
                taken.add(candidate.ref());
            }
        }

        for (BankStatementLine line : lines) {
            if (decisions.containsKey(line.getId())) {
                continue;
            }
            closestByAmountAndDate(line, candidates, taken).ifPresent(candidate -> {
                decisions.put(line.getId(), new Decision(candidate, BankLineMatchStatus.SUGGESTED));
                taken.add(candidate.ref());
            });
        }
        return decisions;
    }

    private static Optional<MatchCandidate> closestByAmountAndDate(BankStatementLine line, List<MatchCandidate> candidates,
                                                                   Set<BankMatchRef> taken) {
        return candidates.stream()
                .filter(candidate -> fitsSide(line, candidate) && !taken.contains(candidate.ref()))
                .filter(candidate -> amountMatches(line, candidate) && withinSettlementWindow(line, candidate))
                .min(Comparator.comparingLong((MatchCandidate candidate) -> daysBetween(candidate, line))
                        .thenComparing(candidate -> candidate.amount().subtract(line.amount()).abs())
                        .thenComparing(MatchCandidate::id));
    }

    static boolean fitsSide(BankStatementLine line, MatchCandidate candidate) {
        boolean debitRecord = candidate.type() == BankMatchRecordType.SUPPLIER_PAYMENT
                || candidate.type() == BankMatchRecordType.CHARGEBACK;
        return line.isCredit() != debitRecord;
    }

    static boolean amountMatches(BankStatementLine line, MatchCandidate candidate) {
        return candidate.amount().subtract(line.amount()).abs().compareTo(AMOUNT_TOLERANCE) <= 0;
    }

    private static boolean withinSettlementWindow(BankStatementLine line, MatchCandidate candidate) {
        long days = daysBetween(candidate, line);
        return days >= 0 && days <= MAX_DAYS_AFTER_RECORD;
    }

    private static long daysBetween(MatchCandidate candidate, BankStatementLine line) {
        return ChronoUnit.DAYS.between(candidate.date(), line.getTxnDate());
    }

    static boolean referenceMatches(BankStatementLine line, String reference) {
        if (reference == null || reference.isBlank() || reference.trim().length() < MIN_REFERENCE_LENGTH) {
            return false;
        }
        String wanted = reference.trim().toUpperCase(Locale.ROOT);
        String utr = line.getUtrReference() != null ? line.getUtrReference().toUpperCase(Locale.ROOT) : "";
        String narration = line.getNarration() != null ? line.getNarration().toUpperCase(Locale.ROOT) : "";
        return utr.contains(wanted) || narration.contains(wanted);
    }
}
