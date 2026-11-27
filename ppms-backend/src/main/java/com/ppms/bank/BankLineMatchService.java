package com.ppms.bank;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Matching statement lines to records, by hand or automatically. Every change takes the pump's row lock and the
 * line's row lock, so two people cannot link the same record to two lines, and is audited.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankLineMatchService {

    private static final Set<BankLineMatchStatus> CLAIMING = EnumSet.of(BankLineMatchStatus.MATCHED, BankLineMatchStatus.SUGGESTED);
    private static final Set<BankLineMatchStatus> CONFIRMED = EnumSet.of(BankLineMatchStatus.MATCHED);
    private static final int CANDIDATE_DAYS_BEFORE = 7;
    private static final int CANDIDATE_DAYS_AFTER = 3;
    private static final int REFERENCE_LOOKBACK_DAYS = 31;
    private static final int CANDIDATE_LIMIT = 50;

    private final BankStatementImportRepository importRepository;
    private final BankStatementLineRepository lineRepository;
    private final BankMatchCandidates candidates;
    private final BankReconciliationViews views;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;

    record AutoMatchCounts(int matched, int suggested) {
    }

    @Transactional
    public StatementLineResponse updateLineMatch(Long pumpId, Long lineId, UpdateLineMatchRequest req, User currentUser) {
        lockPump(pumpId);
        BankStatementLine line = lockedLine(pumpId, lineId);
        BankLineMatchStatus newStatus = requestedStatus(req.getStatus());
        String notes = req.getMatchNotes() != null && !req.getMatchNotes().isBlank() ? req.getMatchNotes().trim() : null;

        switch (newStatus) {
            case MATCHED -> link(pumpId, line, req.links(), notes);
            case IGNORED -> {
                if (notes == null) {
                    throw new BusinessException(ErrorCodes.BANK_LINE_IGNORE_REASON_REQUIRED, Map.of(),
                            "Say why this line is ignored, for example bank charges or a transfer between own accounts.");
                }
                line.clearLinks();
            }
            default -> line.clearLinks();
        }
        line.setMatchStatus(newStatus);
        line.setMatchNotes(notes);
        lineRepository.save(line);
        refreshMatchedCount(line.getImportId());

        BankMatchRef ref = line.linkedRecord();
        AuditAction action = switch (newStatus) {
            case MATCHED -> AuditAction.BANK_LINE_MATCHED;
            case IGNORED -> AuditAction.BANK_LINE_IGNORED;
            default -> AuditAction.BANK_LINE_UNMATCHED;
        };
        auditService.log(pumpId, action, "BankStatementLine", lineId.toString(),
                describe(line) + " set to " + newStatus
                        + (ref != null ? " with " + ref.type() + " #" + ref.id() : "")
                        + (notes != null ? ". Note: " + notes : ""),
                currentUser);
        log.info("Bank line {} set to {} link={} pump={} by={}", lineId, newStatus, ref, pumpId, currentUser.getId());
        return views.line(pumpId, line);
    }

    @Transactional
    public StatementLineResponse acceptSuggestion(Long pumpId, Long lineId, User currentUser) {
        lockPump(pumpId);
        BankStatementLine line = lockedLine(pumpId, lineId);
        BankMatchRef ref = line.linkedRecord();
        if (line.getMatchStatus() != BankLineMatchStatus.SUGGESTED || ref == null) {
            throw new BusinessException(ErrorCodes.BANK_LINE_NO_SUGGESTION, Map.of(),
                    "This line has no suggestion to accept. Match it by hand instead.");
        }
        MatchCandidate record = candidates.require(pumpId, ref);
        BigDecimal difference = record.amount().subtract(line.amount());
        line.setMatchStatus(BankLineMatchStatus.MATCHED);
        if (difference.signum() != 0) {
            line.setMatchNotes("Accepted suggestion; the record differs by ₹" + difference.abs().toPlainString());
        }
        lineRepository.save(line);
        refreshMatchedCount(line.getImportId());

        auditService.log(pumpId, AuditAction.BANK_LINE_MATCHED, "BankStatementLine", lineId.toString(),
                describe(line) + " matched to suggested " + ref.type() + " #" + ref.id(), currentUser);
        log.info("Bank line {} accepted suggestion {} pump={} by={}", lineId, ref, pumpId, currentUser.getId());
        return views.line(pumpId, line);
    }

    /** Re-runs automatic matching over an import's unmatched lines; earlier suggestions are worked out again. */
    @Transactional
    public ImportSummaryResponse autoMatch(Long pumpId, Long importId, User currentUser) {
        lockPump(pumpId);
        BankStatementImport statementImport = importRepository.findById(importId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.STATEMENT_IMPORT, "Import not found"));
        AutoMatchCounts counts = runAutoMatch(pumpId, importId, currentUser);
        return views.summary(statementImport).toBuilder()
                .autoMatchedLines(counts.matched())
                .autoSuggestedLines(counts.suggested())
                .build();
    }

    /** Caller holds the pump lock. */
    AutoMatchCounts runAutoMatch(Long pumpId, Long importId, User currentUser) {
        List<BankStatementLine> previousSuggestions = lineRepository.findByImportIdAndMatchStatusInOrderByTxnDateAscIdAsc(
                importId, EnumSet.of(BankLineMatchStatus.SUGGESTED));
        previousSuggestions.forEach(line -> {
            line.clearLinks();
            line.setMatchStatus(BankLineMatchStatus.UNMATCHED);
        });
        lineRepository.saveAll(previousSuggestions);

        List<BankStatementLine> open = lineRepository.findByImportIdAndMatchStatusInOrderByTxnDateAscIdAsc(
                importId, EnumSet.of(BankLineMatchStatus.UNMATCHED));
        if (open.isEmpty()) {
            return new AutoMatchCounts(0, 0);
        }
        LocalDate first = open.getFirst().getTxnDate();
        LocalDate last = open.getLast().getTxnDate();
        List<MatchCandidate> pool = new ArrayList<>();
        pool.addAll(candidates.open(pumpId, true, first.minusDays(REFERENCE_LOOKBACK_DAYS), last.plusDays(CANDIDATE_DAYS_AFTER), CLAIMING));
        pool.addAll(candidates.open(pumpId, false, first.minusDays(REFERENCE_LOOKBACK_DAYS), last.plusDays(CANDIDATE_DAYS_AFTER), CLAIMING));

        Map<Long, BankAutoMatcher.Decision> decisions = BankAutoMatcher.match(open, pool);
        int matched = 0;
        for (BankStatementLine line : open) {
            BankAutoMatcher.Decision decision = decisions.get(line.getId());
            if (decision == null) {
                continue;
            }
            line.link(decision.candidate().type(), decision.candidate().id());
            line.setMatchStatus(decision.status());
            if (decision.status() == BankLineMatchStatus.MATCHED) {
                matched++;
            }
        }
        lineRepository.saveAll(open);
        refreshMatchedCount(importId);

        AutoMatchCounts counts = new AutoMatchCounts(matched, decisions.size() - matched);
        if (!decisions.isEmpty()) {
            auditService.log(pumpId, AuditAction.BANK_LINES_AUTO_MATCHED, "BankStatementImport", importId.toString(),
                    "Automatic matching: " + counts.matched() + " line(s) matched by reference, "
                            + counts.suggested() + " suggested by amount and date",
                    currentUser);
        }
        log.info("Bank auto-match import={} pump={} matched={} suggested={} open={}",
                importId, pumpId, counts.matched(), counts.suggested(), open.size());
        return counts;
    }

    /**
     * Records the line could be matched to, not matched to another line yet: dated from 7 days before to 3 days after
     * the line unless a range is given, filtered by an amount (within ₹1) or by reference text, closest amount first.
     */
    @Transactional(readOnly = true)
    public List<MatchCandidate> findCandidates(Long pumpId, Long lineId, String search, LocalDate from, LocalDate to) {
        BankStatementLine line = lineOf(pumpId, lineRepository.findById(lineId));
        LocalDate start = from != null ? from : line.getTxnDate().minusDays(CANDIDATE_DAYS_BEFORE);
        LocalDate end = to != null ? to : line.getTxnDate().plusDays(CANDIDATE_DAYS_AFTER);
        Optional<BigDecimal> searchedAmount = parseAmount(search);
        String searchedText = search != null && !search.isBlank() ? search.trim().toUpperCase(Locale.ROOT) : null;

        return candidates.open(pumpId, line.isCredit(), start, end, CONFIRMED).stream()
                .filter(candidate -> searchedText == null || searchedAmount
                        .map(amount -> candidate.amount().subtract(amount).abs().compareTo(BankAutoMatcher.AMOUNT_TOLERANCE) <= 0)
                        .orElseGet(() -> containsText(candidate, searchedText)))
                .sorted(Comparator.comparing((MatchCandidate candidate) -> candidate.amount().subtract(line.amount()).abs())
                        .thenComparing(candidate -> Math.abs(candidate.date().toEpochDay() - line.getTxnDate().toEpochDay())))
                .limit(CANDIDATE_LIMIT)
                .toList();
    }

    private void link(Long pumpId, BankStatementLine line, List<BankMatchRef> links, String notes) {
        if (links.size() != 1) {
            throw new BusinessException(ErrorCodes.BANK_LINE_MATCH_TARGET_REQUIRED, Map.of(),
                    "A matched line must be linked to exactly one record: a settlement, deposit, supplier payment, "
                    + "chargeback, credit payment, shift or sale.");
        }
        BankMatchRef ref = links.getFirst();
        MatchCandidate record = candidates.require(pumpId, ref);
        if (!BankAutoMatcher.fitsSide(line, record)) {
            throw new BusinessException(ErrorCodes.BANK_LINE_DIRECTION_MISMATCH,
                    Map.of("recordTypeCode", ref.type().name()),
                    "A " + ref.type() + " cannot be matched to a " + (line.isCredit() ? "credit" : "debit")
                    + " line: money in matches settlements, deposits and customer payments, money out matches supplier payments"
                    + " and chargebacks.");
        }
        Optional<BankStatementLine> holder = otherLineHolding(ref, line.getId());
        if (holder.isPresent() && holder.get().getMatchStatus() == BankLineMatchStatus.MATCHED) {
            throw new BusinessException(ErrorCodes.BANK_LINE_RECORD_ALREADY_MATCHED,
                    Map.of("recordTypeCode", ref.type().name(), "recordId", ref.id(), "txnDate", holder.get().getTxnDate().toString()),
                    ref.type() + " #" + ref.id() + " is already matched to another bank line (" + holder.get().getTxnDate()
                    + "). Unmatch that line first.");
        }
        if (record.amount().compareTo(line.amount()) != 0 && notes == null) {
            throw new BusinessException(ErrorCodes.BANK_LINE_AMOUNT_MISMATCH_NOTE_REQUIRED,
                    Map.of("lineAmount", line.amount().toPlainString(), "linkedAmount", record.amount().toPlainString()),
                    "The line's amount of ₹" + line.amount() + " does not equal the linked amount of ₹" + record.amount()
                    + ". Add a note explaining the difference to match it anyway.");
        }
        holder.ifPresent(suggestedLine -> {
            suggestedLine.clearLinks();
            suggestedLine.setMatchStatus(BankLineMatchStatus.UNMATCHED);
            lineRepository.saveAndFlush(suggestedLine);
            log.info("Bank line {} lost its suggestion {} to line {}", suggestedLine.getId(), ref, line.getId());
        });
        line.link(ref.type(), ref.id());
    }

    /** The other line linked to a settlement, deposit or payment; shifts and sales may sit on several lines. */
    private Optional<BankStatementLine> otherLineHolding(BankMatchRef ref, Long lineId) {
        Optional<BankStatementLine> holder = switch (ref.type()) {
            case SETTLEMENT -> lineRepository.findByMatchedSettlementId(ref.id());
            case DEPOSIT -> lineRepository.findByMatchedDepositId(ref.id());
            case SUPPLIER_PAYMENT -> lineRepository.findByMatchedSupplierPaymentId(ref.id());
            case CHARGEBACK -> lineRepository.findByMatchedChargebackId(ref.id());
            case CREDIT_PAYMENT -> lineRepository.findByMatchedPaymentId(ref.id());
            case SHIFT, ANCILLARY_SALE -> Optional.empty();
        };
        return holder.filter(found -> !found.getId().equals(lineId));
    }

    private BankLineMatchStatus requestedStatus(String status) {
        if (status == null) {
            throw new BusinessException(ErrorCodes.BANK_LINE_STATUS_REQUIRED, Map.of(),
                    "status is required (MATCHED, IGNORED or UNMATCHED)");
        }
        try {
            BankLineMatchStatus parsed = BankLineMatchStatus.valueOf(status.toUpperCase(Locale.ROOT));
            if (parsed != BankLineMatchStatus.SUGGESTED) {
                return parsed;
            }
        } catch (IllegalArgumentException e) {
            log.debug("Unknown bank line status '{}'", status);
        }
        throw new BusinessException(ErrorCodes.BANK_LINE_STATUS_INVALID, Map.of(),
                "Invalid status. Allowed: MATCHED, IGNORED, UNMATCHED");
    }

    private BankStatementLine lockedLine(Long pumpId, Long lineId) {
        return lineOf(pumpId, lineRepository.findByIdForUpdate(lineId));
    }

    private BankStatementLine lineOf(Long pumpId, Optional<BankStatementLine> found) {
        BankStatementLine line = found.orElseThrow(() ->
                new ResourceNotFoundException(EntityType.STATEMENT_LINE, "Statement line not found"));
        importRepository.findById(line.getImportId())
                .filter(statementImport -> statementImport.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.STATEMENT_LINE, "Statement line not found"));
        return line;
    }

    private void refreshMatchedCount(Long importId) {
        importRepository.findById(importId).ifPresent(statementImport -> {
            statementImport.setMatchedLines(lineRepository.countMatchedByImportId(importId));
            importRepository.save(statementImport);
        });
    }

    private void lockPump(Long pumpId) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
    }

    private static String describe(BankStatementLine line) {
        return "Bank line of " + line.getTxnDate() + " (" + (line.isCredit() ? "credit" : "debit") + " ₹"
                + line.amount().toPlainString() + ")";
    }

    private static boolean containsText(MatchCandidate candidate, String text) {
        return (candidate.reference() != null && candidate.reference().toUpperCase(Locale.ROOT).contains(text))
                || (candidate.description() != null && candidate.description().toUpperCase(Locale.ROOT).contains(text));
    }

    private static Optional<BigDecimal> parseAmount(String search) {
        if (search == null || search.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(search.trim().replace(",", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
