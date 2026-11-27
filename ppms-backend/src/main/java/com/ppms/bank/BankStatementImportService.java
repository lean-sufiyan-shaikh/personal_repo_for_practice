package com.ppms.bank;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementImportService {

    private final BankStatementImportRepository importRepository;
    private final BankStatementLineRepository lineRepository;
    private final BankLineMatchService matchService;
    private final BankReconciliationViews views;
    private final AuditService auditService;
    private final PumpLocationRepository pumpLocationRepository;
    private final BusinessClock businessClock;

    private record ParsedStatement(List<BankStatementLine> lines, int skippedRows) {}

    @Transactional(readOnly = true)
    public List<ImportSummaryResponse> getImports(Long pumpId) {
        return views.summaries(importRepository.findByPumpIdOrderByImportedAtDesc(pumpId));
    }

    @Transactional(readOnly = true)
    public List<StatementLineResponse> getLines(Long pumpId, Long importId) {
        importRepository.findById(importId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.STATEMENT_IMPORT, "Import not found"));
        return views.lines(pumpId, lineRepository.findByImportIdOrderByTxnDateAscIdAsc(importId));
    }

    /**
     * CSV format (first row is a header and is skipped): Date, Narration, Debit, Credit, Balance, UTR.
     * Rows with an unreadable date or amount are skipped and counted, so one bad row does not fail a large import.
     * Lines already imported for the pump (same UTR, or same date, narration, amounts, balance and UTR) are
     * skipped and counted, so an overlapping statement brings in only its new lines. The new lines are then
     * matched automatically.
     */
    @Transactional
    public ImportSummaryResponse importStatement(Long pumpId, MultipartFile file, String bankName, String accountNumber,
                                                 BankStatementDateFormat dateFormat, User currentUser) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_FILE_EMPTY, Map.of(), "Uploaded file is empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".csv")) {
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_NOT_CSV, Map.of(), "Only CSV files are accepted");
        }

        ParsedStatement parsed = parse(pumpId, file, dateFormat != null ? dateFormat : BankStatementDateFormat.AUTO);
        if (parsed.lines().isEmpty()) {
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_NO_ROWS, Map.of(),
                    "No valid transaction rows found in the uploaded file. " +
                    "Ensure the CSV format is: Date, Narration, Debit, Credit, Balance, UTR");
        }

        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        String fileHash = sha256Hex(file);
        importRepository.findFirstByPumpIdAndFileHash(pumpId, fileHash).ifPresent(existing -> {
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_ALREADY_IMPORTED,
                    Map.of("importedOn", existing.getImportedAt().atZoneSameInstant(businessClock.zone()).toLocalDate().toString(),
                           "fromDate", String.valueOf(existing.getStatementFromDate()),
                           "toDate", String.valueOf(existing.getStatementToDate())),
                    "This statement file was already imported on "
                    + existing.getImportedAt().atZoneSameInstant(businessClock.zone()).toLocalDate()
                    + " (" + existing.getStatementFromDate() + " to " + existing.getStatementToDate() + ").");
        });

        List<BankStatementLine> newLines = withoutAlreadyImported(pumpId, parsed.lines());
        int skippedDuplicates = parsed.lines().size() - newLines.size();
        if (newLines.isEmpty()) {
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_NOTHING_NEW, Map.of("count", skippedDuplicates),
                    "All " + skippedDuplicates + " line(s) of this statement were already imported. Nothing new to add.");
        }

        BankStatementImport statementImport = importRepository.save(BankStatementImport.builder()
                .pumpId(pumpId)
                .bankName(bankName.trim())
                .accountNumber(accountNumber != null ? accountNumber.trim() : null)
                .statementFromDate(newLines.stream().map(BankStatementLine::getTxnDate).min(Comparator.naturalOrder()).orElseThrow())
                .statementToDate(newLines.stream().map(BankStatementLine::getTxnDate).max(Comparator.naturalOrder()).orElseThrow())
                .totalLines(newLines.size())
                .matchedLines(0)
                .fileHash(fileHash)
                .importedByUserId(currentUser.getId())
                .build());

        Long importId = statementImport.getId();
        newLines.forEach(line -> line.setImportId(importId));
        lineRepository.saveAll(newLines);

        log.info("Bank statement imported: pump={} bank='{}' lines={} duplicates={} skipped={} period={}/{} by={}",
                pumpId, bankName, newLines.size(), skippedDuplicates, parsed.skippedRows(),
                statementImport.getStatementFromDate(), statementImport.getStatementToDate(), currentUser.getId());
        auditService.log(pumpId, AuditAction.BANK_STATEMENT_IMPORTED,
                "BankStatementImport", importId.toString(),
                "Imported " + newLines.size() + " lines from " + bankName +
                " (" + statementImport.getStatementFromDate() + " to " + statementImport.getStatementToDate() + ")" +
                (skippedDuplicates > 0 ? ", " + skippedDuplicates + " already-imported lines skipped" : "") +
                (parsed.skippedRows() > 0 ? ", " + parsed.skippedRows() + " rows skipped (bad format)" : ""),
                currentUser);

        BankLineMatchService.AutoMatchCounts counts = matchService.runAutoMatch(pumpId, importId, currentUser);
        return views.summary(statementImport).toBuilder()
                .skippedDuplicateLines(skippedDuplicates)
                .skippedInvalidRows(parsed.skippedRows())
                .autoMatchedLines(counts.matched())
                .autoSuggestedLines(counts.suggested())
                .build();
    }

    /**
     * A bank's UTR identifies one transaction, and the line hash identifies a line without one; either already on an
     * earlier statement of the pump means the line was imported before. Within one file nothing is dropped: two equal
     * amounts on the same day are common.
     */
    private List<BankStatementLine> withoutAlreadyImported(Long pumpId, List<BankStatementLine> lines) {
        Set<String> utrReferences = lines.stream()
                .map(BankStatementLine::getUtrReference)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> importedUtrs = utrReferences.isEmpty() ? Set.of()
                : new HashSet<>(lineRepository.findImportedUtrReferences(pumpId, utrReferences));
        Set<String> importedHashes = new HashSet<>(lineRepository.findImportedLineHashes(pumpId,
                lines.stream().map(BankStatementLine::getLineHash).collect(Collectors.toSet())));
        return lines.stream()
                .filter(line -> !importedHashes.contains(line.getLineHash()))
                .filter(line -> line.getUtrReference() == null || !importedUtrs.contains(line.getUtrReference()))
                .toList();
    }

    private String sha256Hex(MultipartFile file) {
        try {
            return sha256Hex(file.getBytes());
        } catch (IOException ex) {
            log.warn("CSV read error while hashing statement: {}", ex.getMessage(), ex);
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_UNREADABLE,
                    Map.of(),
                    "Failed to read the uploaded file. Ensure it is a valid CSV with the required columns.");
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", ex);
        }
    }

    private ParsedStatement parse(Long pumpId, MultipartFile file, BankStatementDateFormat requestedFormat) {
        List<List<String>> rows = new ArrayList<>();
        int skippedRows = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String row;
            boolean headerSkipped = false;
            while ((row = reader.readLine()) != null) {
                if (row.isBlank()) continue;
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                Optional<List<String>> cols = BankStatementCsv.splitLine(row).filter(fields -> fields.size() >= 4);
                if (cols.isEmpty()) {
                    skippedRows++;
                    continue;
                }
                rows.add(cols.get());
            }
        } catch (IOException ex) {
            log.warn("CSV read error for pump={}: {}", pumpId, ex.getMessage(), ex);
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_UNREADABLE,
                    Map.of(),
                    "Failed to read the uploaded file. Ensure it is a valid CSV with the required columns.");
        }

        BankStatementDateFormat format = BankStatementDates.resolve(rows.stream().map(cols -> cols.get(0)).toList(), requestedFormat);
        List<BankStatementLine> lines = new ArrayList<>();
        for (List<String> cols : rows) {
            Optional<BankStatementLine> line = toLine(cols, format);
            if (line.isEmpty()) {
                skippedRows++;
            } else {
                lines.add(line.get());
            }
        }
        return new ParsedStatement(lines, skippedRows);
    }

    private Optional<BankStatementLine> toLine(List<String> cols, BankStatementDateFormat format) {
        LocalDate txnDate = BankStatementDates.parse(cols.get(0), format);
        if (txnDate == null) {
            log.warn("Skipping CSV row — unparseable date: '{}'", cols.get(0));
            return Optional.empty();
        }

        Optional<BigDecimal> debit = BankStatementCsv.parseMoney(cols.get(2));
        Optional<BigDecimal> credit = BankStatementCsv.parseMoney(cols.get(3));
        if (debit.isEmpty() || credit.isEmpty()) {
            log.warn("Skipping CSV row dated {} — unparseable amount (debit='{}', credit='{}')",
                    txnDate, cols.get(2), cols.get(3));
            return Optional.empty();
        }

        String narration = cols.get(1).trim();
        String utr = cols.size() > 5 ? cols.get(5).trim() : null;
        BigDecimal balance = cols.size() > 4 ? BankStatementCsv.parseOptionalBalance(cols.get(4)) : null;
        BankStatementLine line = BankStatementLine.builder()
                .txnDate(txnDate)
                .narration(narration.isEmpty() ? null : narration)
                .debitAmount(debit.get())
                .creditAmount(credit.get())
                .balance(balance)
                .utrReference(utr != null && !utr.isEmpty() ? utr : null)
                .matchStatus(BankLineMatchStatus.UNMATCHED)
                .build();
        line.setLineHash(lineHash(line));
        return Optional.of(line);
    }

    private static String lineHash(BankStatementLine line) {
        String content = String.join("|",
                line.getTxnDate().toString(),
                Objects.toString(line.getNarration(), ""),
                money(line.getDebitAmount()),
                money(line.getCreditAmount()),
                line.getBalance() != null ? money(line.getBalance()) : "",
                Objects.toString(line.getUtrReference(), ""));
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
