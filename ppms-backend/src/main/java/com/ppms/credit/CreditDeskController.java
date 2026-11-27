package com.ppms.credit;

import com.ppms.common.idempotency.IdempotencyService;
import com.ppms.common.idempotency.IdempotentRequest;
import com.ppms.credit.CreditDeskResponses.AdjustmentResponse;
import com.ppms.credit.CreditDeskResponses.DepositResponse;
import com.ppms.credit.CreditDeskResponses.DisputeResponse;
import com.ppms.credit.CreditDeskResponses.ReasonRequest;
import com.ppms.credit.CreditDeskResponses.WriteOffRequest;
import com.ppms.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Supplier;

/**
 * Credit notes, advance refunds, security deposits, write-offs, closing, merging and disputes. Every action that
 * moves money takes an Idempotency-Key header, so a resend returns the first result.
 */
@RestController
@RequestMapping("/api/pumps/{pumpId}/credit-ledger")
@RequiredArgsConstructor
public class CreditDeskController {

    private final CreditNoteService creditNoteService;
    private final CreditAdvanceService advanceService;
    private final CreditDepositService depositService;
    private final CreditAccountClosureService closureService;
    private final CreditClientMergeService mergeService;
    private final CreditDisputeService disputeService;
    private final CreditClientAccessService clientAccessService;
    private final CreditLedgerQueryService queryService;
    private final IdempotencyService idempotencyService;

    /** POST /{clientId}/credit-notes — Owner/Admin; against a sale or charge, or the oldest bills. */
    @PostMapping("/{clientId}/credit-notes")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> issueCreditNote(@PathVariable Long pumpId, @PathVariable Long clientId,
                                             @Valid @RequestBody IssueCreditNoteRequest request,
                                             @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                             HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        return once(currentUser, key, httpRequest, request, () -> {
            CreditNoteService.IssuedCreditNote issued = creditNoteService.issue(pumpId, clientId, request, currentUser);
            return created(AdjustmentResponse.of(issued.adjustment(),
                    issued.gstCreditNote().map(note -> note.getId()).orElse(null),
                    issued.gstCreditNote().map(note -> note.getCreditNoteNumber()).orElse(null)));
        });
    }

    /** POST /{clientId}/advance-refunds — Owner/Admin. */
    @PostMapping("/{clientId}/advance-refunds")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> refundAdvance(@PathVariable Long pumpId, @PathVariable Long clientId,
                                           @Valid @RequestBody MoneyMovementRequest request,
                                           @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                           HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        return once(currentUser, key, httpRequest, request, () ->
                created(AdjustmentResponse.of(advanceService.refundAdvance(pumpId, clientId, request, currentUser))));
    }

    @GetMapping("/{clientId}/deposits")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<DepositResponse>> getDeposits(@PathVariable Long pumpId, @PathVariable Long clientId) {
        return ResponseEntity.ok(depositService.movementsOf(pumpId, clientId).stream().map(DepositResponse::of).toList());
    }

    /** POST /{clientId}/deposits — records a security deposit received. Owner/Admin/Manager. */
    @PostMapping("/{clientId}/deposits")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<?> receiveDeposit(@PathVariable Long pumpId, @PathVariable Long clientId,
                                            @Valid @RequestBody MoneyMovementRequest request,
                                            @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                            HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        return once(currentUser, key, httpRequest, request, () ->
                created(DepositResponse.of(depositService.receive(pumpId, clientId, request, currentUser))));
    }

    @PostMapping("/{clientId}/deposits/apply")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> applyDeposit(@PathVariable Long pumpId, @PathVariable Long clientId,
                                          @Valid @RequestBody MoneyMovementRequest request,
                                          @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                          HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        return once(currentUser, key, httpRequest, request, () ->
                created(DepositResponse.of(depositService.applyToDues(pumpId, clientId, request, currentUser))));
    }

    @PostMapping("/{clientId}/deposits/refund")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> refundDeposit(@PathVariable Long pumpId, @PathVariable Long clientId,
                                           @Valid @RequestBody MoneyMovementRequest request,
                                           @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                           HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        return once(currentUser, key, httpRequest, request, () ->
                created(DepositResponse.of(depositService.refund(pumpId, clientId, request, currentUser))));
    }

    /** POST /{clientId}/write-off — Owner only; without an amount writes off all the account owes. */
    @PostMapping("/{clientId}/write-off")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<?> writeOff(@PathVariable Long pumpId, @PathVariable Long clientId,
                                      @Valid @RequestBody WriteOffRequest request,
                                      @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                      HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        return once(currentUser, key, httpRequest, request, () -> created(AdjustmentResponse.of(
                closureService.writeOff(pumpId, clientId, request.amount(), request.reason(), currentUser))));
    }

    @PostMapping("/clients/{clientId}/close")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<CreditClientResponse> closeAccount(@PathVariable Long pumpId, @PathVariable Long clientId,
                                                             @Valid @RequestBody ReasonRequest request,
                                                             @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(queryService.toClientResponse(
                closureService.close(pumpId, clientId, request.reason(), currentUser)));
    }

    @PostMapping("/clients/{clientId}/reopen")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<CreditClientResponse> reopenAccount(@PathVariable Long pumpId, @PathVariable Long clientId,
                                                              @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(queryService.toClientResponse(closureService.reopen(pumpId, clientId, currentUser)));
    }

    /** POST /clients/{clientId}/merge-into/{targetClientId} — Owner only; returns the target with the merged records. */
    @PostMapping("/clients/{clientId}/merge-into/{targetClientId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<CreditClientResponse> mergeClient(@PathVariable Long pumpId, @PathVariable Long clientId,
                                                            @PathVariable Long targetClientId,
                                                            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(queryService.toClientResponse(
                mergeService.merge(pumpId, clientId, targetClientId, currentUser)));
    }

    /** GET /{clientId}/disputes — every dispute of the account, a main account's sub-accounts included. */
    @GetMapping("/{clientId}/disputes")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<DisputeResponse>> getDisputes(@PathVariable Long pumpId, @PathVariable Long clientId) {
        return ResponseEntity.ok(disputeService.disputesOfAccount(clientAccessService.requireClientForPump(pumpId, clientId))
                .stream().map(DisputeResponse::of).toList());
    }

    @PostMapping("/disputes")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<DisputeResponse> raiseDispute(@PathVariable Long pumpId,
                                                        @Valid @RequestBody RaiseDisputeRequest request,
                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DisputeResponse.of(disputeService.raise(pumpId, request, currentUser)));
    }

    /** POST /disputes/{disputeId}/resolve — Owner/Admin; CREDITED issues a credit note for the disputed amount. */
    @PostMapping("/disputes/{disputeId}/resolve")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> resolveDispute(@PathVariable Long pumpId, @PathVariable Long disputeId,
                                            @Valid @RequestBody ResolveDisputeRequest request,
                                            @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                            HttpServletRequest httpRequest, @AuthenticationPrincipal User currentUser) {
        return once(currentUser, key, httpRequest, request, () ->
                ResponseEntity.ok(DisputeResponse.of(disputeService.resolve(pumpId, disputeId, request, currentUser))));
    }

    private ResponseEntity<?> once(User user, String key, HttpServletRequest httpRequest, Object body,
                                   Supplier<ResponseEntity<?>> action) {
        return idempotencyService.runOnce(IdempotentRequest.of(user.getId(), key, httpRequest, body), action);
    }

    private static ResponseEntity<?> created(Object body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
