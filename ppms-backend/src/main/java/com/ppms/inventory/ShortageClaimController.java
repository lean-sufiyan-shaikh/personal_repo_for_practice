package com.ppms.inventory;

import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory/{pumpId}/shortage-claims")
@RequiredArgsConstructor
public class ShortageClaimController {

    private final ShortageClaimQueryService queryService;
    private final ShortageClaimService claimService;

    /**
     * GET /api/inventory/{pumpId}/shortage-claims?status=&page=0&size=20
     * Claims newest first, optionally of one status, with open and settled totals over every claim.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ShortageClaimListResponse> listClaims(
            @PathVariable Long pumpId,
            @RequestParam(required = false) ShortageClaimStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(queryService.listClaims(pumpId, status, page, size));
    }

    /**
     * GET /api/inventory/{pumpId}/shortage-claims/{claimId}
     * One claim with its nozzle readings and the pump's details, for the printable claim note.
     */
    @GetMapping("/{claimId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ShortageClaimResponse> getClaim(@PathVariable Long pumpId, @PathVariable Long claimId) {
        return ResponseEntity.ok(queryService.getClaim(pumpId, claimId));
    }

    /**
     * PATCH /api/inventory/{pumpId}/shortage-claims/{claimId}/settle
     * Records the supplier's credit note (RAISED → SETTLED).
     */
    @PatchMapping("/{claimId}/settle")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ShortageClaimResponse> settleClaim(
            @PathVariable Long pumpId,
            @PathVariable Long claimId,
            @Valid @RequestBody SettleShortageClaimRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(claimService.settle(pumpId, claimId, request, currentUser));
    }

    /**
     * PATCH /api/inventory/{pumpId}/shortage-claims/{claimId}/reject
     * Records that the supplier refused the claim (RAISED → REJECTED).
     */
    @PatchMapping("/{claimId}/reject")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<ShortageClaimResponse> rejectClaim(
            @PathVariable Long pumpId,
            @PathVariable Long claimId,
            @Valid @RequestBody RejectShortageClaimRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(claimService.reject(pumpId, claimId, request, currentUser));
    }
}
