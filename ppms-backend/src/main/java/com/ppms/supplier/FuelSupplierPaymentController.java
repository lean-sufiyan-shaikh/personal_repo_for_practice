package com.ppms.supplier;

import com.ppms.common.dto.PagedResponse;
import com.ppms.common.idempotency.IdempotencyService;
import com.ppms.common.idempotency.IdempotentRequest;
import com.ppms.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Payments sent to fuel suppliers, against a tanker delivery or as an advance. Only owners and admins see and record
 * them.
 *
 * GET  /api/pumps/{pumpId}/supplier-payments[?supplierId=]   — newest first
 * POST /api/pumps/{pumpId}/supplier-payments                 — record (Idempotency-Key supported)
 * POST /api/pumps/{pumpId}/supplier-payments/{id}/void       — void with a reason
 */
@RestController
@RequestMapping("/api/pumps/{pumpId}/supplier-payments")
@RequiredArgsConstructor
public class FuelSupplierPaymentController {

    private final FuelSupplierPaymentService paymentService;
    private final IdempotencyService idempotencyService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<PagedResponse<SupplierPaymentResponse>> getPayments(
            @PathVariable Long pumpId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(paymentService.list(pumpId, supplierId, page, size));
    }

    /** An Idempotency-Key header makes a resend after a timeout return the first payment instead of recording two. */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> recordPayment(
            @PathVariable Long pumpId,
            @Valid @RequestBody RecordSupplierPaymentRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal User currentUser) {
        return idempotencyService.runOnce(
                IdempotentRequest.of(currentUser.getId(), idempotencyKey, httpRequest, request),
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(paymentService.record(pumpId, request, currentUser)));
    }

    @PostMapping("/{paymentId}/void")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public SupplierPaymentResponse voidPayment(
            @PathVariable Long pumpId,
            @PathVariable Long paymentId,
            @Valid @RequestBody VoidSupplierPaymentRequest request,
            @AuthenticationPrincipal User currentUser) {
        return paymentService.voidPayment(pumpId, paymentId, request.reason(), currentUser);
    }
}
