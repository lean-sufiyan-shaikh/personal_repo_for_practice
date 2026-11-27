package com.ppms.bank;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Builds the import summaries and line responses the reconciliation screens show. */
@Component
@RequiredArgsConstructor
class BankReconciliationViews {

    private final BankStatementLineRepository lineRepository;
    private final BankMatchCandidates candidates;

    private record StatusTotals(int lines, BigDecimal credit, BigDecimal debit) {
        static final StatusTotals NONE = new StatusTotals(0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    List<ImportSummaryResponse> summaries(List<BankStatementImport> imports) {
        if (imports.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<BankLineMatchStatus, StatusTotals>> totals = lineRepository
                .summariseByImportIds(imports.stream().map(BankStatementImport::getId).toList()).stream()
                .collect(Collectors.groupingBy(row -> (Long) row[0], Collectors.toMap(
                        row -> (BankLineMatchStatus) row[1],
                        row -> new StatusTotals(((Number) row[2]).intValue(), (BigDecimal) row[3], (BigDecimal) row[4]))));
        return imports.stream().map(i -> summary(i, totals.getOrDefault(i.getId(), Map.of()))).toList();
    }

    ImportSummaryResponse summary(BankStatementImport statementImport) {
        return summaries(List.of(statementImport)).getFirst();
    }

    List<StatementLineResponse> lines(Long pumpId, List<BankStatementLine> lines) {
        Map<BankMatchRef, MatchCandidate> records = candidates.describe(pumpId,
                lines.stream().map(BankStatementLine::linkedRecord).filter(Objects::nonNull).toList());
        return lines.stream().map(line -> line(line, records)).toList();
    }

    StatementLineResponse line(Long pumpId, BankStatementLine line) {
        return lines(pumpId, List.of(line)).getFirst();
    }

    private ImportSummaryResponse summary(BankStatementImport i, Map<BankLineMatchStatus, StatusTotals> byStatus) {
        StatusTotals unmatched = byStatus.getOrDefault(BankLineMatchStatus.UNMATCHED, StatusTotals.NONE);
        StatusTotals suggested = byStatus.getOrDefault(BankLineMatchStatus.SUGGESTED, StatusTotals.NONE);
        return ImportSummaryResponse.builder()
                .id(i.getId())
                .pumpId(i.getPumpId())
                .bankName(i.getBankName())
                .accountNumber(i.getAccountNumber())
                .statementFromDate(i.getStatementFromDate())
                .statementToDate(i.getStatementToDate())
                .totalLines(i.getTotalLines())
                .matchedLines(byStatus.getOrDefault(BankLineMatchStatus.MATCHED, StatusTotals.NONE).lines())
                .suggestedLines(suggested.lines())
                .ignoredLines(byStatus.getOrDefault(BankLineMatchStatus.IGNORED, StatusTotals.NONE).lines())
                .unmatchedLines(unmatched.lines())
                .unmatchedCreditAmount(unmatched.credit().add(suggested.credit()))
                .unmatchedDebitAmount(unmatched.debit().add(suggested.debit()))
                .importedByUserId(i.getImportedByUserId())
                .importedAt(i.getImportedAt())
                .build();
    }

    private StatementLineResponse line(BankStatementLine l, Map<BankMatchRef, MatchCandidate> records) {
        BankMatchRef ref = l.linkedRecord();
        return StatementLineResponse.builder()
                .id(l.getId())
                .importId(l.getImportId())
                .txnDate(l.getTxnDate())
                .narration(l.getNarration())
                .debitAmount(l.getDebitAmount())
                .creditAmount(l.getCreditAmount())
                .balance(l.getBalance())
                .utrReference(l.getUtrReference())
                .matchStatus(l.getMatchStatus().name())
                .matchedShiftId(l.getMatchedShiftId())
                .matchedAncillarySaleId(l.getMatchedAncillarySaleId())
                .matchedPaymentId(l.getMatchedPaymentId())
                .matchedSettlementId(l.getMatchedSettlementId())
                .matchedDepositId(l.getMatchedDepositId())
                .matchedSupplierPaymentId(l.getMatchedSupplierPaymentId())
                .matchedChargebackId(l.getMatchedChargebackId())
                .matchedRecord(ref != null ? records.get(ref) : null)
                .matchNotes(l.getMatchNotes())
                .createdAt(l.getCreatedAt())
                .build();
    }
}
