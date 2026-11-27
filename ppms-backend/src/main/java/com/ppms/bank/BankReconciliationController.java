package com.ppms.bank;

import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/pumps/{pumpId}/bank-reconciliation")
@RequiredArgsConstructor
public class BankReconciliationController {

    private final BankStatementImportService importService;
    private final BankLineMatchService matchService;

    @GetMapping("/imports")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<ImportSummaryResponse>> getImports(@PathVariable Long pumpId) {
        return ResponseEntity.ok(importService.getImports(pumpId));
    }

    @GetMapping("/imports/{importId}/lines")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<StatementLineResponse>> getLines(
            @PathVariable Long pumpId,
            @PathVariable Long importId) {
        return ResponseEntity.ok(importService.getLines(pumpId, importId));
    }

    /** Multipart fields: file (CSV), bankName, optional accountNumber, optional dateFormat (AUTO, DAY_FIRST, MONTH_FIRST). */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ImportSummaryResponse> importStatement(
            @PathVariable Long pumpId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("bankName") String bankName,
            @RequestParam(value = "accountNumber", required = false) String accountNumber,
            @RequestParam(value = "dateFormat", defaultValue = "AUTO") BankStatementDateFormat dateFormat,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importService.importStatement(pumpId, file, bankName, accountNumber, dateFormat, currentUser));
    }

    /** Matches the import's unmatched lines again, e.g. after settlements or deposits were recorded. */
    @PostMapping("/imports/{importId}/auto-match")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ImportSummaryResponse> autoMatch(
            @PathVariable Long pumpId,
            @PathVariable Long importId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(matchService.autoMatch(pumpId, importId, currentUser));
    }

    /** Records the line can still be matched to; search is an amount or reference text. */
    @GetMapping("/lines/{lineId}/candidates")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<MatchCandidate>> getCandidates(
            @PathVariable Long pumpId,
            @PathVariable Long lineId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(matchService.findCandidates(pumpId, lineId, search, from, to));
    }

    /** Body: status (MATCHED, IGNORED or UNMATCHED), one matched*Id for MATCHED, matchNotes (the reason for IGNORED). */
    @PatchMapping("/lines/{lineId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<StatementLineResponse> updateLineMatch(
            @PathVariable Long pumpId,
            @PathVariable Long lineId,
            @Valid @RequestBody UpdateLineMatchRequest body,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(matchService.updateLineMatch(pumpId, lineId, body, currentUser));
    }

    @PostMapping("/lines/{lineId}/accept-suggestion")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<StatementLineResponse> acceptSuggestion(
            @PathVariable Long pumpId,
            @PathVariable Long lineId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(matchService.acceptSuggestion(pumpId, lineId, currentUser));
    }
}
