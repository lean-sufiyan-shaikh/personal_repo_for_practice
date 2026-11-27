package com.ppms.bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BankAutoMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 5, 10);

    @Test
    void aSettlementWhoseBankReferenceIsTheLinesUtrIsMatched() {
        BankStatementLine line = credit(1L, DAY, "9787.60", "UPI SETTLEMENT", "UTR123456");
        MatchCandidate settlement = candidate(BankMatchRecordType.SETTLEMENT, 7L, DAY.minusDays(10), "9787.60", "utr123456");

        Map<Long, BankAutoMatcher.Decision> decisions = BankAutoMatcher.match(List.of(line), List.of(settlement));

        assertThat(decisions.get(1L).candidate()).isEqualTo(settlement);
        assertThat(decisions.get(1L).status()).isEqualTo(BankLineMatchStatus.MATCHED);
    }

    @Test
    void aDepositWhoseSlipIsInTheNarrationIsMatched() {
        BankStatementLine line = credit(1L, DAY, "20000.00", "CASH DEP SLIP-4471 BRANCH", null);
        MatchCandidate deposit = candidate(BankMatchRecordType.DEPOSIT, 3L, DAY, "20000.00", "SLIP-4471");

        assertThat(BankAutoMatcher.match(List.of(line), List.of(deposit)).get(1L).status())
                .isEqualTo(BankLineMatchStatus.MATCHED);
    }

    @Test
    void aSupplierPaymentMatchesOnlyADebitLine() {
        BankStatementLine debit = debit(1L, DAY, "500000.00", "NEFT IOCL", "N12345678");
        BankStatementLine creditLine = credit(2L, DAY, "500000.00", "NEFT IOCL", "N12345678");
        MatchCandidate payment = candidate(BankMatchRecordType.SUPPLIER_PAYMENT, 9L, DAY, "500000.00", "N12345678");

        Map<Long, BankAutoMatcher.Decision> decisions = BankAutoMatcher.match(List.of(debit, creditLine), List.of(payment));

        assertThat(decisions.get(1L).status()).isEqualTo(BankLineMatchStatus.MATCHED);
        assertThat(decisions).doesNotContainKey(2L);
    }

    @Test
    void aReferenceMatchWithADifferentAmountIsOnlySuggested() {
        BankStatementLine line = credit(1L, DAY, "9500.00", "UPI", "UTR555555");
        MatchCandidate settlement = candidate(BankMatchRecordType.SETTLEMENT, 7L, DAY, "9787.60", "UTR555555");

        assertThat(BankAutoMatcher.match(List.of(line), List.of(settlement)).get(1L).status())
                .isEqualTo(BankLineMatchStatus.SUGGESTED);
    }

    @Test
    void anAmountWithinOneRupeeAndUpToThreeDaysAfterTheRecordIsSuggested() {
        BankStatementLine line = credit(1L, DAY, "9787.00", "UPI SETTLEMENT", null);
        MatchCandidate settlement = candidate(BankMatchRecordType.SETTLEMENT, 7L, DAY.minusDays(3), "9787.60", null);

        BankAutoMatcher.Decision decision = BankAutoMatcher.match(List.of(line), List.of(settlement)).get(1L);

        assertThat(decision.status()).isEqualTo(BankLineMatchStatus.SUGGESTED);
        assertThat(decision.candidate().id()).isEqualTo(7L);
    }

    @Test
    void aRecordFourDaysOlderOrDatedAfterTheLineIsNotSuggested() {
        BankStatementLine line = credit(1L, DAY, "100.00", "UPI", null);
        MatchCandidate tooOld = candidate(BankMatchRecordType.SETTLEMENT, 1L, DAY.minusDays(4), "100.00", null);
        MatchCandidate later = candidate(BankMatchRecordType.SETTLEMENT, 2L, DAY.plusDays(1), "100.00", null);

        assertThat(BankAutoMatcher.match(List.of(line), List.of(tooOld, later))).isEmpty();
    }

    @Test
    void twoEqualLinesAndTwoEqualRecordsAreSuggestedOneEachClosestDateFirst() {
        BankStatementLine first = credit(1L, DAY, "100.00", "UPI", null);
        BankStatementLine second = credit(2L, DAY.plusDays(1), "100.00", "UPI", null);
        MatchCandidate older = candidate(BankMatchRecordType.SETTLEMENT, 10L, DAY.minusDays(1), "100.00", null);
        MatchCandidate newer = candidate(BankMatchRecordType.SETTLEMENT, 11L, DAY, "100.00", null);

        Map<Long, BankAutoMatcher.Decision> decisions = BankAutoMatcher.match(List.of(first, second), List.of(older, newer));

        assertThat(decisions.get(1L).candidate().id()).isEqualTo(11L);
        assertThat(decisions.get(2L).candidate().id()).isEqualTo(10L);
        assertThat(decisions.values()).allMatch(decision -> decision.status() == BankLineMatchStatus.SUGGESTED);
    }

    @Test
    void aReferenceShownOnTwoRecordsIsLeftToTheAmountRuleAsASuggestion() {
        BankStatementLine line = credit(1L, DAY, "100.00", "REF ABCD1234", null);
        MatchCandidate one = candidate(BankMatchRecordType.DEPOSIT, 1L, DAY, "100.00", "ABCD1234");
        MatchCandidate two = candidate(BankMatchRecordType.SETTLEMENT, 2L, DAY, "100.00", "ABCD1234");

        assertThat(BankAutoMatcher.match(List.of(line), List.of(one, two)).get(1L).status())
                .isEqualTo(BankLineMatchStatus.SUGGESTED);
    }

    @Test
    void shortReferencesAreNotTrusted() {
        BankStatementLine line = credit(1L, DAY, "100.00", "PAYMENT 12", null);

        assertThat(BankAutoMatcher.referenceMatches(line, "12")).isFalse();
    }

    private static BankStatementLine credit(Long id, LocalDate date, String amount, String narration, String utr) {
        return BankStatementLine.builder().id(id).txnDate(date).narration(narration).utrReference(utr)
                .creditAmount(new BigDecimal(amount)).debitAmount(BigDecimal.ZERO).build();
    }

    private static BankStatementLine debit(Long id, LocalDate date, String amount, String narration, String utr) {
        return BankStatementLine.builder().id(id).txnDate(date).narration(narration).utrReference(utr)
                .creditAmount(BigDecimal.ZERO).debitAmount(new BigDecimal(amount)).build();
    }

    private static MatchCandidate candidate(BankMatchRecordType type, Long id, LocalDate date, String amount, String reference) {
        return new MatchCandidate(type, id, date, new BigDecimal(amount), reference, null);
    }
}
