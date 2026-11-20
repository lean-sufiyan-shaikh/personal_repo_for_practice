package com.ppms.pump;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/pumps")
@RequiredArgsConstructor
public class PumpController {

    private final PumpLocationRepository pumpLocationRepository;
    private final DispensaryUnitRepository duRepository;
    private final AuditService auditService;
    private final PumpShiftDefinitionService shiftDefinitionService;
    private final TankService tankService;
    private final DispensaryUnitService dispensaryUnitService;
    private final PumpService pumpService;

    // ─────────────────────────────────────────────────────────────────────────
    // PUMPS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/pumps
     * Returns pumps visible to the current user with their active DUs and nozzles.
     *   - OWNER     → all enabled pumps they own
     *   - ADMIN / MANAGER / OPERATOR → the single pump they are assigned to
     */
    @GetMapping
    public ResponseEntity<List<PumpResponse>> getMyPumps(@AuthenticationPrincipal User currentUser) {
        List<PumpResponse> pumps;

        if (currentUser.getRole() == UserRole.OWNER) {
            pumps = pumpLocationRepository.findByOwnerIdAndEnabledTrue(currentUser.getId())
                    .stream()
                    .map(this::toPumpResponse)
                    .toList();
        } else if (currentUser.getAssignedPumpId() != null) {
            pumps = pumpLocationRepository.findById(currentUser.getAssignedPumpId())
                    .filter(PumpLocation::isEnabled)
                    .map(p -> List.of(toPumpResponse(p)))
                    .orElse(List.of());
        } else {
            pumps = List.of();
        }

        return ResponseEntity.ok(pumps);
    }

    /**
     * POST /api/pumps
     * Creates a new pump location. SUPER_ADMIN only.
     */
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<PumpResponse> createPump(
            @Valid @RequestBody CreatePumpRequest request,
            @AuthenticationPrincipal User currentUser) {

        if (pumpLocationRepository.existsByOwnerIdAndName(currentUser.getId(), request.getName())) {
            throw new BusinessException(
                    "A pump named '" + request.getName() + "' already exists. Please use a different name.");
        }

        PumpLocation pump = PumpLocation.builder()
                .ownerId(currentUser.getId())
                .name(request.getName())
                .address(request.getAddress())
                .maxDuCount(request.getMaxDuCount())
                .build();

        pump = pumpLocationRepository.save(pump);

        auditService.log(pump.getId(), AuditAction.PUMP_CREATED,
                "PumpLocation", pump.getId().toString(),
                "Pump created: " + pump.getName() + " (ownerId=" + pump.getOwnerId() + ")",
                currentUser);

        return ResponseEntity.status(HttpStatus.CREATED).body(toPumpResponse(pump));
    }

    /**
     * DELETE /api/pumps/{pumpId}
     * Owner only; refused once the pump has shifts, staff or other recorded data.
     */
    @DeleteMapping("/{pumpId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> deletePump(
            @PathVariable Long pumpId,
            @AuthenticationPrincipal User currentUser) {
        pumpService.deletePump(pumpId, currentUser);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UNDERGROUND TANKS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{pumpId}/tanks")
    public ResponseEntity<List<TankResponse>> getTanks(@PathVariable Long pumpId) {
        return ResponseEntity.ok(tankService.getTanks(pumpId));
    }

    @PostMapping("/{pumpId}/tanks")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TankResponse> createTank(
            @PathVariable Long pumpId,
            @Valid @RequestBody CreateTankRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tankService.createTank(pumpId, request, currentUser));
    }

    @PatchMapping("/{pumpId}/tanks/{tankId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TankResponse> updateTank(
            @PathVariable Long pumpId,
            @PathVariable Long tankId,
            @Valid @RequestBody UpdateTankRequest request) {
        return ResponseEntity.ok(tankService.updateTank(pumpId, tankId, request));
    }

    @PatchMapping("/{pumpId}/tanks/{tankId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TankResponse> updateTankStatus(
            @PathVariable Long pumpId,
            @PathVariable Long tankId,
            @Valid @RequestBody UpdateTankStatusRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(tankService.updateTankStatus(pumpId, tankId, request.status(), currentUser));
    }

    /**
     * PATCH /api/pumps/{pumpId}/max-dus
     * Increases the maximum DU count for a pump. Owner/Admin only.
     */
    @PatchMapping("/{pumpId}/max-dus")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<PumpResponse> updateMaxDuCount(
            @PathVariable Long pumpId,
            @Valid @RequestBody UpdateMaxNozzleCountRequest request,
            @AuthenticationPrincipal User currentUser) {

        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Pump not found"));

        long currentDuCount = duRepository.countByPumpId(pumpId);
        if (request.maxDuCount() < currentDuCount) {
            throw new BusinessException(
                    "Cannot set max DUs to " + request.maxDuCount() +
                    " — the pump already has " + currentDuCount + " DUs configured. " +
                    "The new limit must be at least " + currentDuCount + ".");
        }

        pump.setMaxDuCount(request.maxDuCount());
        pumpLocationRepository.save(pump);

        log.info("Max DU count for pump {} updated to {} by user={}", pumpId, request.maxDuCount(), currentUser.getId());

        return ResponseEntity.ok(toPumpResponse(pump));
    }

    /**
     * PATCH /api/pumps/{pumpId}/settings
     * Updates configurable pump thresholds (discrepancy escalation, expense approval).
     * Owner/Admin only.
     */
    @PatchMapping("/{pumpId}/settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Transactional
    public ResponseEntity<PumpResponse> updatePumpSettings(
            @PathVariable Long pumpId,
            @RequestBody UpdatePumpSettingsRequest request,
            @AuthenticationPrincipal User currentUser) {

        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Pump not found"));

        if (request.discrepancyEscalationThreshold() != null) {
            if (request.discrepancyEscalationThreshold().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Discrepancy escalation threshold cannot be negative.");
            }
            pump.setDiscrepancyEscalationThreshold(
                    request.discrepancyEscalationThreshold().compareTo(BigDecimal.ZERO) == 0
                    ? null
                    : request.discrepancyEscalationThreshold().setScale(2, RoundingMode.HALF_UP));
        }

        if (request.expenseApprovalThreshold() != null) {
            if (request.expenseApprovalThreshold().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Expense approval threshold cannot be negative.");
            }
            pump.setExpenseApprovalThreshold(
                    request.expenseApprovalThreshold().compareTo(BigDecimal.ZERO) == 0
                    ? null
                    : request.expenseApprovalThreshold().setScale(2, RoundingMode.HALF_UP));
        }

        pumpLocationRepository.save(pump);
        log.info("Pump {} settings updated by user={}: escalationThreshold={}, expenseApprovalThreshold={}",
                pumpId, currentUser.getId(),
                pump.getDiscrepancyEscalationThreshold(), pump.getExpenseApprovalThreshold());

        return ResponseEntity.ok(toPumpResponse(pump));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHIFT DEFINITIONS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{pumpId}/shift-definitions")
    public ResponseEntity<List<ShiftDefinitionResponse>> getShiftDefinitions(@PathVariable Long pumpId) {
        return ResponseEntity.ok(shiftDefinitionService.getForPump(pumpId));
    }

    @GetMapping("/{pumpId}/shift-definitions/active")
    public ResponseEntity<List<ShiftDefinitionResponse>> getActiveShiftDefinitions(@PathVariable Long pumpId) {
        return ResponseEntity.ok(shiftDefinitionService.getActiveForPump(pumpId));
    }

    /**
     * GET /api/pumps/{pumpId}/shift-definitions/for-date?date=YYYY-MM-DD
     * Returns the shift definitions that were active for a pump on a given historical date.
     * Used by the backfill modal to populate the shift window selector.
     */
    @GetMapping("/{pumpId}/shift-definitions/for-date")
    public ResponseEntity<List<ShiftDefinitionResponse>> getShiftDefinitionsForDate(
            @PathVariable Long pumpId,
            @RequestParam LocalDate date) {
        return ResponseEntity.ok(shiftDefinitionService.getForPumpOnDate(pumpId, date));
    }

    @PostMapping("/{pumpId}/shift-definitions")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<ShiftDefinitionResponse>> createShiftDefinitions(
            @PathVariable Long pumpId,
            @RequestBody List<@Valid CreateShiftDefinitionRequest> requests,
            @AuthenticationPrincipal User currentUser) {
        List<ShiftDefinitionResponse> created =
                shiftDefinitionService.createBatch(pumpId, requests, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{pumpId}/shift-definitions")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Void> deleteShiftDefinitionGroup(
            @PathVariable Long pumpId,
            @RequestParam LocalDate effectiveFrom,
            @RequestParam(required = false) LocalDate effectiveTo) {
        shiftDefinitionService.deleteGroup(pumpId, effectiveFrom, effectiveTo);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{pumpId}/shift-definitions/disable")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<List<ShiftDefinitionResponse>> disableShiftDefinitionGroup(
            @PathVariable Long pumpId,
            @RequestParam LocalDate effectiveFrom,
            @Valid @RequestBody DisableShiftGroupRequest req) {
        List<ShiftDefinitionResponse> updated =
                shiftDefinitionService.disableGroup(pumpId, effectiveFrom, req.getDisableDate());
        return ResponseEntity.ok(updated);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private PumpResponse toPumpResponse(PumpLocation pump) {
        List<DUResponse> activeDUs = dispensaryUnitService.getActiveDUs(pump.getId());

        return PumpResponse.builder()
                .id(pump.getId())
                .name(pump.getName())
                .address(pump.getAddress())
                .maxDuCount(pump.getMaxDuCount())
                .ownerId(pump.getOwnerId())
                .createdAt(pump.getCreatedAt())
                .discrepancyEscalationThreshold(pump.getDiscrepancyEscalationThreshold())
                .expenseApprovalThreshold(pump.getExpenseApprovalThreshold())
                .dus(activeDUs)
                .build();
    }
}
