package com.ppms.credit;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pumps/{pumpId}/credit-clients")
@RequiredArgsConstructor
public class CreditClientController {

    private final CreditClientRepository creditClientRepository;
    private final CreditInterestService interestService;
    private final CreditClientService creditClientService;
    private final CreditAccountPolicyService creditAccountPolicyService;
    private final CreditClientEditService clientEditService;

    /**
     * GET /api/pumps/{pumpId}/credit-clients
     * Returns all credit clients for a pump, sorted by name (parents first, then sub-accounts).
     * Each response includes hierarchy metadata: parentClientId, parentClientName, isParent.
     * All authenticated users can read — operators need the list to select from when closing a shift, so they get
     * only what the picker shows (see {@link CreditClientPickerResponse}); other roles get the full account.
     */
    @GetMapping
    public ResponseEntity<List<?>> getClients(@PathVariable Long pumpId, @AuthenticationPrincipal User currentUser) {
        List<CreditClient> allClients = creditClientRepository.findByPumpIdOrderByNameAsc(pumpId);

        // Build a name-by-ID map for resolving parent names without N+1 queries
        Map<Long, String> nameById = allClients.stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));

        // Determine which root accounts actually have sub-accounts
        Set<Long> idsWithChildren = allClients.stream()
                .filter(c -> c.getParentClientId() != null)
                .map(CreditClient::getParentClientId)
                .collect(Collectors.toSet());

        if (currentUser.getRole() == UserRole.OPERATOR) {
            return ResponseEntity.ok(allClients.stream()
                    .sorted(Comparator
                            .comparing((CreditClient c) -> c.isActive() ? 0 : 1)
                            .thenComparing(CreditClient::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(CreditClient::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(c -> CreditClientPickerResponse.from(c, nameById,
                            c.getParentClientId() == null && idsWithChildren.contains(c.getId())))
                    .toList());
        }

        // Sub-accounts always share their parent's pump, so every child is in allClients.
        Map<Long, BigDecimal> ownOutstandingById = interestService.computeOutstandingBatch(
                allClients.stream().map(CreditClient::getId).toList());
        Map<Long, BigDecimal> childrenOutstandingByParentId = allClients.stream()
                .filter(c -> c.getParentClientId() != null)
                .collect(Collectors.toMap(CreditClient::getParentClientId,
                        c -> ownOutstandingById.getOrDefault(c.getId(), BigDecimal.ZERO), BigDecimal::add));

        List<CreditClientResponse> clients = allClients.stream()
                .map(c -> {
                    boolean isParent = c.getParentClientId() == null && idsWithChildren.contains(c.getId());
                    BigDecimal own = ownOutstandingById.getOrDefault(c.getId(), BigDecimal.ZERO);
                    BigDecimal outstanding = isParent
                            ? own.add(childrenOutstandingByParentId.getOrDefault(c.getId(), BigDecimal.ZERO))
                                    .setScale(2, RoundingMode.HALF_UP)
                            : own;
                    return toResponse(c, nameById, isParent, outstanding);
                })
                .sorted(Comparator
                        // Active clients first, disabled last
                        .comparing((CreditClientResponse r) -> Boolean.TRUE.equals(r.getIsActive()) ? 0 : 1)
                        .thenComparing(CreditClientResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CreditClientResponse::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return ResponseEntity.ok(clients);
    }

    /**
     * GET /api/pumps/{pumpId}/credit-clients/credit-standing
     * Whether each client could take a credit sale now, and how much credit its group has left.
     * All authenticated users can read — operators pick clients for credit sales.
     */
    @GetMapping("/credit-standing")
    public ResponseEntity<List<CreditClientStandingResponse>> getCreditStanding(@PathVariable Long pumpId) {
        return ResponseEntity.ok(List.copyOf(creditAccountPolicyService.creditStandingOfPumpClients(pumpId).values()));
    }

    /**
     * POST /api/pumps/{pumpId}/credit-clients
     * Creates a credit client; with parentClientId it becomes a sub-account (max 1 level deep).
     * A main account starts with the pump's default credit limit.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditClientResponse> createClient(
            @PathVariable Long pumpId,
            @Valid @RequestBody CreateCreditClientRequest request,
            @AuthenticationPrincipal User currentUser) {
        CreditClient client = clientEditService.createClient(pumpId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(client, pumpId));
    }

    /**
     * PATCH /api/pumps/{pumpId}/credit-clients/{clientId}
     * Updates name, phone, notes, GSTIN and/or billing address; only non-null fields are applied.
     * Changing the phone or GSTIN needs an Owner or Admin, and a new phone turns off the balance-page PIN.
     */
    @PatchMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditClientResponse> updateClient(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @Valid @RequestBody UpdateCreditClientRequest request,
            @AuthenticationPrincipal User currentUser) {
        CreditClient client = clientEditService.updateClient(pumpId, clientId, request, currentUser);
        return ResponseEntity.ok(toResponse(client, pumpId));
    }

    /**
     * PATCH /api/pumps/{pumpId}/credit-clients/{clientId}/status
     * Enables or disables a credit client (soft toggle); enabling a disabled client needs an Owner or Admin.
     * Disabled clients are hidden from shift credit-entry dropdowns and shown at the bottom of the Clients list.
     * All historical data is preserved; sub-accounts keep their own active flags.
     */
    @PatchMapping("/{clientId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditClientResponse> toggleStatus(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal User currentUser) {

        Boolean active = body.get("isActive");
        if (active == null) throw new BusinessException(ErrorCodes.CREDIT_CLIENT_ACTIVE_FLAG_REQUIRED,
                Map.of(),
                "isActive is required");

        CreditClient client = clientEditService.changeStatus(pumpId, clientId, active, currentUser);
        return ResponseEntity.ok(toResponse(client, pumpId));
    }

    /**
     * DELETE /api/pumps/{pumpId}/credit-clients/{clientId}
     * Removes a client that has no sub-accounts and no ledger history; clients with history are deactivated instead.
     */
    @DeleteMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> deleteClient(@PathVariable Long pumpId, @PathVariable Long clientId) {
        creditClientService.deleteClient(pumpId, clientId);
        return ResponseEntity.noContent().build();
    }

    /** PUT /api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin — sets or replaces the client's balance-page PIN. */
    @PutMapping("/{clientId}/portal-pin")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> setPortalPin(@PathVariable Long pumpId, @PathVariable Long clientId,
                                             @Valid @RequestBody SetPortalPinRequest request) {
        creditClientService.setPortalPin(pumpId, clientId, request.pin());
        return ResponseEntity.noContent().build();
    }

    /** DELETE /api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin — turns off the client's balance-page access. */
    @DeleteMapping("/{clientId}/portal-pin")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> removePortalPin(@PathVariable Long pumpId, @PathVariable Long clientId) {
        creditClientService.removePortalPin(pumpId, clientId);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** A parent's outstanding includes all its sub-accounts; others show their own balance only. */
    private CreditClientResponse toResponse(CreditClient c, Long pumpId) {
        Map<Long, String> nameById = creditClientRepository.findByPumpIdOrderByNameAsc(pumpId)
                .stream().collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));
        boolean isParent = c.getParentClientId() == null && !creditClientRepository.findByParentClientId(c.getId()).isEmpty();
        BigDecimal outstanding = isParent
                ? interestService.computeOutstandingWithChildren(c.getId())
                : interestService.computeOutstanding(c.getId());
        return toResponse(c, nameById, isParent, outstanding);
    }

    private CreditClientResponse toResponse(CreditClient c, Map<Long, String> nameById, boolean isParent,
                                            BigDecimal outstanding) {
        return CreditClientResponse.builder()
                .id(c.getId())
                .pumpId(c.getPumpId())
                .name(c.getName())
                .phone(c.getPhone())
                .notes(c.getNotes())
                .creditLimit(c.getCreditLimit())
                .unlimited(c.isUnlimitedCredit())
                .outstandingBalance(outstanding)
                .monthlyInterestRate(c.getMonthlyInterestRate())
                .interestGraceDays(c.getInterestGraceDays())
                .createdAt(c.getCreatedAt())
                .parentClientId(c.getParentClientId())
                .parentClientName(c.getParentClientId() != null ? nameById.get(c.getParentClientId()) : null)
                .isParent(isParent)
                .isActive(c.isActive())
                .hasPortalPin(c.getPortalPinHash() != null)
                .weakPortalPin(c.getPortalPinHash() != null && Boolean.TRUE.equals(c.getPortalPinWeak()))
                .gstin(c.getGstin())
                .billingAddress(c.getBillingAddress())
                .build();
    }
}
