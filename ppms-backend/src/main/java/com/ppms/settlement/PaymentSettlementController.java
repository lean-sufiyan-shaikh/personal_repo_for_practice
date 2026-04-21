package com.ppms.settlement;

import com.ppms.common.dto.PagedResponse;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manages payment settlement records and per-pump alert configurations.
 *
 * All endpoints are scoped to a pump via /api/pumps/{pumpId}/...
 * Only OWNER and ADMIN can record settlements or update configs — Managers have read access.
 */
@RestController
@RequestMapping("/api/pumps")
@RequiredArgsConstructor
public class PaymentSettlementController {

    private final PaymentSettlementService service;

    // ── Settlement configs ────────────────────────────────────────────────────

    /**
     * GET /api/pumps/{pumpId}/settlement-configs
     * Returns alert configs for all three payment types (UPI, CARD, FLEET_CARD).
     * Returns defaults (18:00, enabled=true) for types not yet explicitly configured.
     */
    @GetMapping("/{pumpId}/settlement-configs")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public List<PaymentSettlementService.ConfigResponse> getConfigs(@PathVariable Long pumpId) {
        return service.getConfigs(pumpId);
    }

    /**
     * PUT /api/pumps/{pumpId}/settlement-configs/{paymentType}
     * Creates or updates the alert config for one payment type.
     * paymentType: UPI | CARD | FLEET_CARD
     */
    @PutMapping("/{pumpId}/settlement-configs/{paymentType}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public PaymentSettlementService.ConfigResponse upsertConfig(
            @PathVariable Long pumpId,
            @PathVariable String paymentType,
            @RequestBody PaymentSettlementService.UpdateConfigRequest req,
            @AuthenticationPrincipal User currentUser) {
        return service.upsertConfig(pumpId, paymentType, req, currentUser);
    }

    // ── Settlement records ────────────────────────────────────────────────────

    /**
     * GET /api/pumps/{pumpId}/settlements?page=0&size=20&paymentType=UPI
     * Paginated list of settlement records, newest first.
     * paymentType is optional — omit to return all types.
     */
    @GetMapping("/{pumpId}/settlements")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public PagedResponse<PaymentSettlementService.SettlementResponse> list(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String paymentType) {
        return service.list(pumpId, paymentType, PageRequest.of(page, size));
    }

    /**
     * POST /api/pumps/{pumpId}/settlements
     * Records a new settlement entry (amount received in bank for a given date).
     */
    @PostMapping("/{pumpId}/settlements")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentSettlementService.SettlementResponse record(
            @PathVariable Long pumpId,
            @RequestBody PaymentSettlementService.RecordSettlementRequest req,
            @AuthenticationPrincipal User currentUser) {
        return service.record(pumpId, req, currentUser);
    }

    /**
     * GET /api/pumps/{pumpId}/settlements/wallet
     * Returns the current wallet balance per payment type:
     *   pending = SUM(all shift collections) − SUM(all settlements recorded)
     */
    @GetMapping("/{pumpId}/settlements/wallet")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public PaymentSettlementService.WalletSummary getWallet(@PathVariable Long pumpId) {
        return service.getWalletSummary(pumpId);
    }
}
