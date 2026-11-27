package com.ppms.pettycash;

import com.ppms.common.dto.PagedResponse;
import com.ppms.common.idempotency.IdempotencyService;
import com.ppms.common.idempotency.IdempotentRequest;
import com.ppms.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** The petty cash box: managers, admins and owners operate it; only owners and admins set its float. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/petty-cash")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
public class PettyCashController {

    private final PettyCashService pettyCashService;
    private final IdempotencyService idempotencyService;

    /** GET /api/pumps/{pumpId}/petty-cash — float, balance, the top-up that refills it and the latest count. */
    @GetMapping
    public PettyCashResponses.Summary summary(@PathVariable Long pumpId) {
        return pettyCashService.summary(pumpId);
    }

    /** GET /api/pumps/{pumpId}/petty-cash/ledger?from=&to=&page=0&size=20 — movements with the balance after each. */
    @GetMapping("/ledger")
    public PagedResponse<PettyCashResponses.Entry> ledger(
            @PathVariable Long pumpId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pettyCashService.ledger(pumpId, from, to, page, size);
    }

    /** GET /api/pumps/{pumpId}/petty-cash/counts?page=0&size=10 — counts newest first. */
    @GetMapping("/counts")
    public PagedResponse<PettyCashResponses.Count> counts(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return pettyCashService.counts(pumpId, page, size);
    }

    /** PUT /api/pumps/{pumpId}/petty-cash/settings — the float and the count tolerance. */
    @PutMapping("/settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public PettyCashResponses.Summary updateSettings(
            @PathVariable Long pumpId,
            @Valid @RequestBody PettyCashRequests.UpdateSettings request,
            @AuthenticationPrincipal User currentUser) {
        return pettyCashService.updateSettings(pumpId, request, currentUser);
    }

    /** POST /api/pumps/{pumpId}/petty-cash/top-ups — moves cash from the safe into the box; Idempotency-Key honoured. */
    @PostMapping("/top-ups")
    public ResponseEntity<?> topUp(
            @PathVariable Long pumpId,
            @Valid @RequestBody PettyCashRequests.TopUp request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal User currentUser) {
        return idempotencyService.runOnce(
                IdempotentRequest.of(currentUser.getId(), idempotencyKey, httpRequest, request),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(pettyCashService.topUp(pumpId, request, currentUser)));
    }

    /** POST /api/pumps/{pumpId}/petty-cash/counts — records a count for today or an earlier open day. */
    @PostMapping("/counts")
    public ResponseEntity<?> recordCount(
            @PathVariable Long pumpId,
            @Valid @RequestBody PettyCashRequests.Count request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal User currentUser) {
        return idempotencyService.runOnce(
                IdempotentRequest.of(currentUser.getId(), idempotencyKey, httpRequest, request),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(pettyCashService.recordCount(pumpId, request, currentUser)));
    }
}
