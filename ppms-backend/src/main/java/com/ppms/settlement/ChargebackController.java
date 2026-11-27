package com.ppms.settlement;

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

/** Chargebacks and refunds: managers can see them; only owners and admins record them. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/chargebacks")
@RequiredArgsConstructor
public class ChargebackController {

    private final ChargebackService chargebackService;
    private final IdempotencyService idempotencyService;

    /** GET /api/pumps/{pumpId}/chargebacks?page=0&size=20 — newest first. */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public PagedResponse<ChargebackResponse> list(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return chargebackService.list(pumpId, page, size);
    }

    /** POST /api/pumps/{pumpId}/chargebacks — an Idempotency-Key header makes a resend return the first record. */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<?> record(
            @PathVariable Long pumpId,
            @Valid @RequestBody RecordChargebackRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal User currentUser) {
        return idempotencyService.runOnce(
                IdempotentRequest.of(currentUser.getId(), idempotencyKey, httpRequest, request),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(chargebackService.record(pumpId, request, currentUser)));
    }
}
