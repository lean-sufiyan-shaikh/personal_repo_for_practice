package com.ppms.cash;

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

import java.util.List;

/**
 * Cash drops from an open shift into the safe. Operators record drops on their own shift; a manager, admin or owner
 * other than the recorder confirms or rejects each one.
 */
@RestController
@RequestMapping("/api/pumps/{pumpId}")
@RequiredArgsConstructor
public class CashDropController {

    private final CashDropService cashDropService;
    private final IdempotencyService idempotencyService;

    /**
     * POST /api/pumps/{pumpId}/shifts/{shiftId}/cash-drops
     * An Idempotency-Key header makes a resend after a timeout return the first drop instead of recording two.
     */
    @PostMapping("/shifts/{shiftId}/cash-drops")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<?> recordDrop(
            @PathVariable Long pumpId,
            @PathVariable Long shiftId,
            @Valid @RequestBody RecordCashDropRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal User currentUser) {
        return idempotencyService.runOnce(
                IdempotentRequest.of(currentUser.getId(), idempotencyKey, httpRequest, request),
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(cashDropService.recordDrop(pumpId, shiftId, request, currentUser)));
    }

    /** GET /api/pumps/{pumpId}/shifts/{shiftId}/cash-drops — every drop of the shift, newest first. */
    @GetMapping("/shifts/{shiftId}/cash-drops")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public List<CashDropResponse> dropsForShift(
            @PathVariable Long pumpId,
            @PathVariable Long shiftId,
            @AuthenticationPrincipal User currentUser) {
        return cashDropService.dropsForShift(pumpId, shiftId, currentUser);
    }

    /** GET /api/pumps/{pumpId}/cash-drops/pending — drops waiting for confirmation, oldest first. */
    @GetMapping("/cash-drops/pending")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public List<CashDropResponse> pendingDrops(@PathVariable Long pumpId) {
        return cashDropService.pendingDrops(pumpId);
    }

    /** PATCH /api/pumps/{pumpId}/cash-drops/{dropId}/confirm — the cash is in the safe (PENDING → CONFIRMED). */
    @PatchMapping("/cash-drops/{dropId}/confirm")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public CashDropResponse confirmDrop(
            @PathVariable Long pumpId,
            @PathVariable Long dropId,
            @AuthenticationPrincipal User currentUser) {
        return cashDropService.confirmDrop(pumpId, dropId, currentUser);
    }

    /** PATCH /api/pumps/{pumpId}/cash-drops/{dropId}/reject — the cash did not arrive as recorded (PENDING → REJECTED). */
    @PatchMapping("/cash-drops/{dropId}/reject")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public CashDropResponse rejectDrop(
            @PathVariable Long pumpId,
            @PathVariable Long dropId,
            @Valid @RequestBody RejectCashDropRequest request,
            @AuthenticationPrincipal User currentUser) {
        return cashDropService.rejectDrop(pumpId, dropId, request, currentUser);
    }
}
