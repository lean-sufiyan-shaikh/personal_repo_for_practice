package com.ppms.cash;

import com.ppms.common.dto.PagedResponse;
import com.ppms.common.idempotency.IdempotencyService;
import com.ppms.common.idempotency.IdempotentRequest;
import com.ppms.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/** The pump safe: balance, ledger, counts and bank deposits. Managers, admins and owners only. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/safe")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
public class SafeController {

    private final SafeService safeService;
    private final BankDepositService bankDepositService;
    private final IdempotencyService idempotencyService;

    /** GET /api/pumps/{pumpId}/safe — balance, drops waiting for confirmation and the latest count. */
    @GetMapping
    public SafeSummaryResponse summary(@PathVariable Long pumpId) {
        return safeService.summary(pumpId);
    }

    /** GET /api/pumps/{pumpId}/safe/ledger?from=&to=&page=0&size=20 — money movements with the balance after each. */
    @GetMapping("/ledger")
    public PagedResponse<SafeLedgerEntryResponse> ledger(
            @PathVariable Long pumpId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return safeService.ledger(pumpId, from, to, page, size);
    }

    /** GET /api/pumps/{pumpId}/safe/counts?page=0&size=10 — counts newest first. */
    @GetMapping("/counts")
    public PagedResponse<SafeCountResponse> counts(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return safeService.counts(pumpId, page, size);
    }

    /** GET /api/pumps/{pumpId}/safe/expected-balance?date= — the balance at the end of that day, or right now for today. */
    @GetMapping("/expected-balance")
    public SafeDayBalanceResponse expectedBalance(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return safeService.expectedBalance(pumpId, date);
    }

    /**
     * POST /api/pumps/{pumpId}/safe/counts — records a count for today, or for countForDate: yesterday until the
     * pump's morning cut-off, any earlier open day by an owner or admin with a reason.
     */
    @PostMapping("/counts")
    @ResponseStatus(HttpStatus.CREATED)
    public SafeCountResponse recordCount(
            @PathVariable Long pumpId,
            @Valid @RequestBody RecordSafeCountRequest request,
            @AuthenticationPrincipal User currentUser) {
        return safeService.recordCount(pumpId, request, currentUser);
    }

    /** GET /api/pumps/{pumpId}/safe/deposits?from=&to=&bankAccount=&page=0&size=20 — with count and total of all matches. */
    @GetMapping("/deposits")
    public BankDepositListResponse deposits(
            @PathVariable Long pumpId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String bankAccount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return bankDepositService.listDeposits(pumpId, from, to, bankAccount, page, size);
    }

    /**
     * POST /api/pumps/{pumpId}/safe/deposits
     * An Idempotency-Key header makes a resend after a timeout return the first deposit instead of recording two.
     */
    @PostMapping("/deposits")
    public ResponseEntity<?> recordDeposit(
            @PathVariable Long pumpId,
            @Valid @RequestBody RecordBankDepositRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal User currentUser) {
        return idempotencyService.runOnce(
                IdempotentRequest.of(currentUser.getId(), idempotencyKey, httpRequest, request),
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(bankDepositService.recordDeposit(pumpId, request, currentUser)));
    }

    /** PUT /api/pumps/{pumpId}/safe/deposits/{depositId}/slip — attaches or replaces the slip (JPEG, PNG or PDF, at most 5 MB). */
    @PutMapping(value = "/deposits/{depositId}/slip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BankDepositResponse attachSlip(
            @PathVariable Long pumpId,
            @PathVariable Long depositId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser) {
        return bankDepositService.attachSlip(pumpId, depositId, file, currentUser);
    }

    /** GET /api/pumps/{pumpId}/safe/deposits/{depositId}/slip — the slip file, shown inline. */
    @GetMapping("/deposits/{depositId}/slip")
    public ResponseEntity<byte[]> slip(@PathVariable Long pumpId, @PathVariable Long depositId) {
        BankDepositSlip slip = bankDepositService.findSlip(pumpId, depositId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(slip.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(slip.getFileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(slip.getContent());
    }
}
