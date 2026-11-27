package com.ppms.shift;

import com.ppms.fleet.PendingVehicleUsage;
import com.ppms.fleet.VehicleCreditInput;
import com.ppms.cash.CashDenominations;
import com.ppms.cash.CashDropService;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.DateRangeBounds;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditAccountPolicyService;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditInterestService;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.invoice.InvoicedSaleGuard;
import com.ppms.pump.CurrentShiftWindow;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpShiftDefinition;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String OPERATOR_OPEN_SHIFT_INDEX = "uq_shift_one_open_per_operator";

    private final ShiftRepository shiftRepository;
    private final ShiftNozzleRepository shiftNozzleRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final NozzleRepository nozzleRepository;
    private final DispensaryUnitRepository duRepository;
    private final UserRepository userRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final CreditAccountPolicyService creditAccountPolicyService;
    private final CreditInterestService creditInterestService;
    private final ShiftLifecycleSupportService lifecycleSupportService;
    private final ShiftReadModelService shiftReadModelService;
    private final com.ppms.planning.ShiftPlanningService shiftPlanningService;
    private final Counter shiftOpenedCounter;
    private final Counter shiftClosedCounter;
    private final ActiveNozzleAssignmentRepository activeNozzleRepository;
    private final ShiftFuelDeductionService fuelDeductionService;
    private final ShiftTestLitresService testLitresService;
    private final BusinessClock businessClock;
    private final InvoicedSaleGuard invoicedSaleGuard;
    private final BalanceSheetStalenessService balanceSheetStalenessService;
    private final CashDropService cashDropService;
    private final ClosedDayGuard closedDayGuard;
    private final ShiftInvestigationNoteRepository investigationNoteRepository;
    private final ShiftBlindCountService blindCountService;
    private final ShiftOperatorService shiftOperatorService;
    private final ShiftOperatorShareService operatorShareService;
    private final NozzleTurnService nozzleTurnService;
    private final ShiftReliefService reliefService;

    /**
     * Opens a new shift for selected nozzles on a Dispensary Unit.
     *
     * Rules:
     * - All nozzles must belong to the specified DU
     * - Each nozzle must not already have an open shift
     * - The operator must not already have an open shift
     * - A fuel price must exist for every nozzle's fuel type
     * - Every nozzle must be mapped to an active tank
     * - Start readings come from each nozzle's stored lastReading (auto pre-filled)
     */
    @Transactional
    public ShiftResponse openShift(Long pumpId, OpenShiftRequest request, User currentUser) {
        DispensaryUnit du = duRepository.findById(request.getDuId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.DISPENSARY_UNIT,
                        "Dispensary Unit not found: " + request.getDuId()));

        if (!du.getPumpId().equals(pumpId)) {
            throw new ResourceNotFoundException(EntityType.DISPENSARY_UNIT, "Dispensary Unit not found");
        }

        lifecycleSupportService.rejectDuplicateNozzles(request.getNozzleIds());
        Map<Long, Nozzle> nozzleById = nozzleRepository.findAllById(request.getNozzleIds()).stream()
                .collect(Collectors.toMap(Nozzle::getId, Function.identity()));
        List<Nozzle> nozzles = request.getNozzleIds().stream()
                .map(id -> Optional.ofNullable(nozzleById.get(id))
                        .orElseThrow(() -> new ResourceNotFoundException(EntityType.NOZZLE, "Nozzle not found: " + id)))
                .toList();

        User operator = lifecycleSupportService.findOperatorForPump(request.getOperatorId(), pumpId);

        CurrentShiftWindow window = lifecycleSupportService.validateShiftCanOpen(du, nozzles, operator, request);
        PumpShiftDefinition shiftDef = window.definition();
        closedDayGuard.requireOpen(pumpId, window.shiftDate());

        OffsetDateTime now = businessClock.now();

        Shift shift = Shift.builder()
                .pumpId(pumpId)
                .duId(du.getId())
                .operatorId(operator.getId())
                .openedByUserId(currentUser.getId())
                .shiftDefinitionId(shiftDef.getId())
                .shiftName(shiftDef.getName())
                .isNightShift(shiftDef.isNightShift())
                .shiftDate(window.shiftDate())
                .actualStartTime(now)
                .status(ShiftStatus.OPEN)
                .isOverdueFlag(false)
                .build();

        return startShift(shift, du, nozzles, operator, currentUser);
    }

    /**
     * Opens the incoming operator's shift after a handover closed the outgoing one: same nozzles, shift window and
     * business date, starting when the outgoing shift ended, from the readings it closed with.
     */
    @Transactional
    public ShiftResponse openShiftForHandover(Shift closedShift, User incomingOperator, User currentUser) {
        List<Nozzle> nozzles = nozzleRepository.findAllById(shiftNozzleRepository.findNozzleIdsByShiftId(closedShift.getId()));
        DispensaryUnit du = duRepository.findById(closedShift.getDuId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.DISPENSARY_UNIT,
                        "Dispensary Unit not found: " + closedShift.getDuId()));
        nozzles.forEach(lifecycleSupportService::rejectExpiredCalibration);
        lifecycleSupportService.rejectNightShiftWithoutConsent(
                Boolean.TRUE.equals(closedShift.getIsNightShift()), incomingOperator);

        Shift shift = Shift.builder()
                .pumpId(closedShift.getPumpId())
                .duId(closedShift.getDuId())
                .operatorId(incomingOperator.getId())
                .openedByUserId(currentUser.getId())
                .shiftDefinitionId(closedShift.getShiftDefinitionId())
                .shiftName(closedShift.getShiftName())
                .isNightShift(closedShift.getIsNightShift())
                .shiftDate(closedShift.getShiftDate())
                .actualStartTime(closedShift.getActualEndTime())
                .status(ShiftStatus.OPEN)
                .isOverdueFlag(false)
                .build();

        return startShift(shift, du, nozzles, incomingOperator, currentUser);
    }

    private ShiftResponse startShift(Shift shift, DispensaryUnit du, List<Nozzle> nozzles, User operator, User currentUser) {
        Long pumpId = shift.getPumpId();
        shift = saveNewShiftForOperator(shift, operator);
        final Long shiftId = shift.getId();

        // Persist nozzle assignments in the join table (batch insert)
        shiftNozzleRepository.saveAll(nozzles.stream()
                .map(nozzle -> ShiftNozzle.builder()
                        .shiftId(shiftId)
                        .nozzleId(nozzle.getId())
                        .build())
                .toList());

        // DB-level nozzle exclusivity guard: a nozzle already assigned means a concurrent request
        // raced past the application-level check in validateShiftCanOpen.
        for (Nozzle nozzle : nozzles) {
            if (activeNozzleRepository.assignIfFree(nozzle.getId(), shiftId) == 0) {
                throw new BusinessException(ErrorCodes.NOZZLES_ALREADY_IN_USE, Map.of(),
                        "One or more selected nozzles are already in use on an active shift. " +
                        "Please refresh the page and try again.");
            }
        }

        // Create opening readings — one per nozzle (each nozzle = one fuel type)
        lifecycleSupportService.createOpeningReadings(shiftId, pumpId, nozzles);

        log.info("Shift {} opened: du={}, nozzles={}, operator={}, openedBy={}",
                shiftId, du.getId(), nozzles.stream().map(Nozzle::getId).toList(), operator.getId(), currentUser.getId());
        shiftOpenedCounter.increment();

        shiftPlanningService.reconcileOnShiftOpen(
                pumpId, shift.getShiftDate(), shift.getShiftDefinitionId(), operator.getId());

        List<ShiftFuelReading> readings = fuelReadingRepository.findByShiftId(shiftId);
        return shiftReadModelService.toResponse(shift, du, nozzles, operator, currentUser.getFullName(), readings, Collections.emptyList());
    }

    /**
     * The unique index on an operator's open shift is the final guard when two opens for the same operator race past
     * the check in validateShiftCanOpen.
     */
    private Shift saveNewShiftForOperator(Shift shift, User operator) {
        try {
            return shiftRepository.saveAndFlush(shift);
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (!cause.contains(OPERATOR_OPEN_SHIFT_INDEX)) {
                throw e;
            }
            log.warn("Concurrent open refused for operator {}: {}", operator.getId(), cause);
            throw new BusinessException(ErrorCodes.OPERATOR_SHIFT_OPENED_CONCURRENTLY,
                    Map.of("operatorName", operator.getFullName()),
                    operator.getFullName() + " was just given another open shift. Refresh the page to see it.");
        }
    }

    /**
     * Closes a shift.
     *
     * Steps:
     * 1. Validate end readings provided for every nozzle that was opened
     * 2. Calculate units sold per nozzle (with meter rollover support)
     * 3. Compute total amount due = sum of (units × snapshotted price) across all nozzles
     * 4. Validate payment breakdown sums match total due (or flag discrepancy)
     * 5. Validate credit entries if creditTotal > 0
     * 6. FIFO inventory deduction per nozzle
     * 7. Update each nozzle's last_reading for pre-fill on next shift open
     */
    @Transactional
    public ShiftResponse closeShift(Long pumpId, Long shiftId, CloseShiftRequest request, User currentUser) {
        Shift shift = findShiftForUpdate(pumpId, shiftId);

        if (!shift.getStatus().isOpen()) {
            throw new BusinessException(ErrorCodes.SHIFT_NOT_OPEN,
                    Map.of("statusCode", shift.getStatus().name()),
                    "Shift is not open. Current status: " + shift.getStatus());
        }
        closedDayGuard.requireOpen(pumpId, shift.getShiftDate());
        blindCountService.requireCloseMatchesCount(shift, request);

        List<Long> nozzleIds = shiftNozzleRepository.findNozzleIdsByShiftId(shiftId);
        List<Nozzle> nozzles = nozzleRepository.findAllById(nozzleIds);
        Map<Long, Nozzle> nozzleById = nozzles.stream()
                .collect(Collectors.toMap(Nozzle::getId, Function.identity()));

        Map<Long, BigDecimal> maxMeterByNozzleId = nozzles.stream()
                .collect(Collectors.toMap(Nozzle::getId, Nozzle::getMaxMeterValue));

        CashDenominations.requireMatches(request.getCashDenominations(), request.getCashCollected());
        BigDecimal cashDropped = cashDropService.confirmedTotalForClose(shiftId);
        operatorShareService.requireCollectionsAddUp(shift, request, cashDropped);

        List<ShiftFuelReading> readings = fuelReadingRepository.findByShiftId(shiftId);
        ShiftLifecycleSupportService.ClosingSummary closingSummary =
                lifecycleSupportService.processClosingReadings(pumpId, maxMeterByNozzleId, request, cashDropped, readings);
        testLitresService.requireNoteAndAuditAboveMax(pumpId, shiftId, readings, request.getTestLitresNote(), currentUser);
        requireReasonForDiscrepancy(request, closingSummary);
        List<CloseShiftRequest.CreditEntryRequest> creditEntries =
                lifecycleSupportService.validateCloseCreditEntries(shiftId, request.getCreditTotal(), request.getCreditEntries(),
                        request.getReplacedCreditEntryIds());

        deductSoldFuel(shift, readings);

        applyClosingValues(shift, request, cashDropped, closingSummary, currentUser);
        shift = shiftRepository.save(shift);
        recordOperatorShares(shift, readings, maxMeterByNozzleId, request, currentUser);

        activeNozzleRepository.deleteByShiftId(shiftId);
        balanceSheetStalenessService.markSheetsOfShiftStale(shift.getPumpId(), shift.getShiftDate(),
                shift.getShiftDefinitionId(), StaleReasons.shiftClosed(shiftId));

        log.info("Shift {} closed: totalDue={}, totalCollected={}, discrepancy={}, status={}",
                shiftId, closingSummary.getTotalDue(), closingSummary.getTotalCollected(),
                closingSummary.getDiscrepancyAmount(), closingSummary.getStatus());
        shiftClosedCounter.increment();

        rememberLastReadings(readings, nozzleById);

        lifecycleSupportService.createCashCollectionEvent(shift, currentUser, nozzles);
        lifecycleSupportService.voidReplacedCreditEntries(shift.getPumpId(), request.getReplacedCreditEntryIds(), currentUser);
        lifecycleSupportService.persistCloseCreditEntries(shift.getId(), shift.getPumpId(), shift.getShiftDate(), creditEntries,
                ShiftLifecycleSupportService.closeCreditOverrideOf(request, shift.getId(), currentUser), currentUser);

        User operator = userRepository.findById(shift.getOperatorId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.OPERATOR, "Operator not found"));
        DispensaryUnit du = duRepository.findById(shift.getDuId()).orElse(null);
        List<ShiftCreditEntry> savedEntries = creditEntryRepository.findByShiftId(shift.getId());
        List<ShiftFuelReading> finalReadings = fuelReadingRepository.findByShiftId(shift.getId());
        return shiftReadModelService.toResponse(shift, du, nozzles, operator, currentUser.getFullName(), finalReadings, savedEntries);
    }

    /** Running reliefs end with the shift, every turn ends at the closing reading, and each operator's share is saved. */
    private void recordOperatorShares(Shift shift, List<ShiftFuelReading> readings, Map<Long, BigDecimal> maxMeterByNozzleId,
                                      CloseShiftRequest request, User closedBy) {
        reliefService.endActiveReliefsAtClose(shift.getId(), shift.getClosedAt(), closedBy);
        List<ShiftNozzleTurn> turns = nozzleTurnService.finishTurnsAtClose(shift, readings, maxMeterByNozzleId, shift.getClosedAt());
        shiftOperatorService.releaseAllAtClose(shift.getId(), shift.getClosedAt());
        operatorShareService.recordAtClose(shift, readings, turns, maxMeterByNozzleId, request.getOperatorCollections());
    }

    private void deductSoldFuel(Shift shift, List<ShiftFuelReading> readings) {
        fuelDeductionService.lockTanksForDeduction(shift.getPumpId(),
                readings.stream().map(ShiftFuelReading::getTankId).filter(Objects::nonNull).toList(),
                readings.stream().filter(reading -> reading.getTankId() == null).map(ShiftFuelReading::getFuelType).toList());
        for (ShiftFuelReading reading : readings) {
            if (reading.getUnitsSold() != null && reading.getUnitsSold().compareTo(ZERO) > 0) {
                reading.setStockShortfallLitres(fuelDeductionService.deductForShiftClose(shift.getId(), shift.getPumpId(),
                        reading.getFuelType(), reading.getTankId(), reading.getUnitsSold(), shift.getActualStartTime()));
            }
        }
        fuelReadingRepository.saveAll(readings);
        shift.setStockShortfall(readings.stream().anyMatch(reading -> reading.getStockShortfallLitres().signum() > 0));
    }

    /** Mirrors the close form: an operator must say why the cash does not match whenever it is short or over. */
    private static void requireReasonForDiscrepancy(CloseShiftRequest request,
                                                    ShiftLifecycleSupportService.ClosingSummary closingSummary) {
        if (closingSummary.getDiscrepancyType() != null
                && (request.getDiscrepancyReason() == null || request.getDiscrepancyReason().isBlank())) {
            throw new BusinessException(ErrorCodes.DISCREPANCY_REASON_REQUIRED,
                    Map.of("expectedAmount", closingSummary.getTotalDue().toPlainString(),
                            "collectedAmount", closingSummary.getTotalCollected().toPlainString()),
                    "A discrepancy reason is required when the collected amount does not match the expected amount. " +
                    "Expected: ₹" + closingSummary.getTotalDue() + ", Collected: ₹" + closingSummary.getTotalCollected() + ".");
        }
    }

    private void applyClosingValues(Shift shift, CloseShiftRequest request, BigDecimal cashDropped,
                                    ShiftLifecycleSupportService.ClosingSummary closingSummary, User closedBy) {
        // A late close is recorded at the scheduled end, so DAY balance-sheet windows stay aligned
        // with the shift definition; the real close time is kept separately.
        OffsetDateTime scheduledEnd = shiftReadModelService.computeScheduledEndTime(shift);
        OffsetDateTime closeTime = businessClock.now();
        shift.setActualEndTime(scheduledEnd != null && closeTime.isAfter(scheduledEnd) ? scheduledEnd : closeTime);
        shift.setClosedAt(closeTime);
        shift.setRoundingAdjustment(closingSummary.getRoundingAdjustment());
        shift.setClosedByUserId(closedBy.getId());
        shift.setTotalAmountDue(closingSummary.getTotalDue());
        shift.setCashCollected(request.getCashCollected().add(cashDropped));
        shift.setCashDropped(cashDropped);
        shift.setCashDenominations(request.getCashDenominations());
        shift.setUpiCollected(request.getUpiCollected());
        shift.setCardCollected(request.getCardCollected());
        shift.setFleetCardCollected(request.getFleetCardCollected());
        shift.setCreditTotal(request.getCreditTotal());
        shift.setDiscrepancyAmount(closingSummary.getDiscrepancyAmount().compareTo(ZERO) > 0
                ? closingSummary.getDiscrepancyAmount() : null);
        shift.setDiscrepancyType(closingSummary.getDiscrepancyType());
        shift.setDiscrepancyReason(request.getDiscrepancyReason() != null && !request.getDiscrepancyReason().isBlank()
                ? request.getDiscrepancyReason().trim() : null);
        shift.setStatus(lifecycleSupportService.escalateStatusIfNeeded(closingSummary.getStatus(),
                closingSummary.getDiscrepancyAmount(), shift.getPumpId()));
    }

    // The next shift on a nozzle pre-fills its opening reading from here.
    private void rememberLastReadings(List<ShiftFuelReading> readings, Map<Long, Nozzle> nozzleById) {
        List<Nozzle> updatedNozzles = new ArrayList<>();
        for (ShiftFuelReading reading : readings) {
            Nozzle nozzle = nozzleById.get(reading.getNozzleId());
            if (nozzle != null) {
                nozzle.setLastReading(reading.getEndReading());
                updatedNozzles.add(nozzle);
            }
        }
        nozzleRepository.saveAll(updatedNozzles);
    }

    private Shift findShiftForUpdate(Long pumpId, Long shiftId) {
        return shiftRepository.findByIdAndPumpIdForUpdate(shiftId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
    }

    /**
     * Adds a credit entry to an open shift mid-shift.
     */
    @Transactional
    public ShiftResponse addCreditEntry(Long pumpId, Long shiftId, AddCreditEntryRequest request, User currentUser) {
        Shift shift = findShiftForUpdate(pumpId, shiftId);

        if (!shift.getStatus().isOpen()) {
            throw new BusinessException(ErrorCodes.CREDIT_ENTRY_SHIFT_NOT_OPEN,
                    Map.of(),
                    "Cannot add credit entries to a shift that is not open.");
        }

        if (currentUser.getRole() == UserRole.OPERATOR && !shiftOperatorService.isWorking(shift, currentUser.getId())) {
            throw new BusinessException(ErrorCodes.CREDIT_ENTRY_NOT_OWN_SHIFT,
                    Map.of(),
                    "Operators can only add credit entries to their own active shift.");
        }
        closedDayGuard.requireOpen(pumpId, shift.getShiftDate());

        CreditClient matchedClient = creditAccountPolicyService.requireClientForCreditEntry(
                shift.getPumpId(), request.getClientId(), request.getClientName());
        creditAccountPolicyService.validateCreditSaleAllowed(shift.getPumpId(), matchedClient, request.getAmount());

        creditEntryRepository.save(lifecycleSupportService.buildCreditEntry(shiftId, matchedClient,
                request.getClientName().trim(),
                request.getBillNo() != null ? request.getBillNo().trim() : null,
                request.getAmount().setScale(2, RoundingMode.HALF_UP),
                shift.getShiftDate(),
                request.getFuelType(),
                request.getDescription() != null ? request.getDescription().trim() : null,
                new VehicleCreditInput(request.getVehicleId(), request.getVehicleRegistration(), request.getDriverName(),
                        request.getDriverPhone()),
                lifecycleSupportService.creditPricesOf(shiftId),
                new PendingVehicleUsage()));

        log.info("Credit entry added mid-shift: shiftId={}, client={}, amount={}, by={}",
                shiftId, request.getClientName(), request.getAmount(), currentUser.getId());

        return shiftReadModelService.toResponseWithLookups(shift);
    }

    /**
     * Resolves a pending discrepancy, or with PENDING_INVESTIGATION only marks it as under investigation.
     */
    @Transactional
    public ShiftResponse resolveDiscrepancy(Long pumpId, Long shiftId, ResolveDiscrepancyRequest request, User currentUser) {
        Shift shift = findShiftForUpdate(pumpId, shiftId);

        boolean isPending         = shift.getStatus() == ShiftStatus.CLOSED_DISCREPANCY_PENDING;
        boolean isPendingApproval = shift.getStatus() == ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL;
        if (!isPending && !isPendingApproval) {
            throw new BusinessException(ErrorCodes.NO_PENDING_DISCREPANCY,
                    Map.of("statusCode", shift.getStatus().name()),
                    "Shift has no pending discrepancy to resolve. Current status: " + shift.getStatus());
        }

        if (isPendingApproval) {
            if (!isOwnerOrAdmin(currentUser)) {
                throw new BusinessException(ErrorCodes.ESCALATED_DISCREPANCY_NEEDS_OWNER, Map.of(),
                        "This discrepancy exceeds the escalation threshold and requires Owner or Admin approval. " +
                        "Managers cannot resolve escalated discrepancies.");
            }
        }

        if (request.resolutionAction() == DiscrepancyResolution.PENDING_INVESTIGATION) {
            return startInvestigation(shift, request.resolutionNote(), currentUser);
        }

        if (request.resolutionAction() == DiscrepancyResolution.WAIVED
                && (request.resolutionNote() == null || request.resolutionNote().isBlank())) {
            throw new BusinessException(ErrorCodes.WAIVER_REASON_REQUIRED, Map.of(),
                    "A mandatory reason is required when waiving a discrepancy (Business Rule 19).");
        }
        if (request.resolutionAction() == DiscrepancyResolution.WAIVED
                && currentUser.getId().equals(shift.getClosedByUserId()) && !isOwnerOrAdmin(currentUser)) {
            throw new BusinessException(ErrorCodes.WAIVER_BY_CLOSER_NOT_ALLOWED, Map.of(),
                    "You closed this shift, so you cannot waive its discrepancy. Ask another manager or the owner.");
        }

        // CASH_RECOVERY only makes sense for SHORT discrepancies — the operator
        // physically returns the cash they failed to hand in. An OVER discrepancy
        // means the operator handed in excess money; there is nothing to recover.
        if (request.resolutionAction() == DiscrepancyResolution.CASH_RECOVERY
                && shift.getDiscrepancyType() != DiscrepancyType.SHORT) {
            throw new BusinessException(ErrorCodes.CASH_RECOVERY_ONLY_FOR_SHORT, Map.of(),
                    "CASH_RECOVERY can only be applied to SHORT discrepancies. " +
                    "Use WAIVED or PENDING_INVESTIGATION for OVER discrepancies.");
        }

        if (request.resolutionAction() == DiscrepancyResolution.SALARY_DEDUCTION
                && shift.getDiscrepancyType() != DiscrepancyType.SHORT) {
            throw new BusinessException(ErrorCodes.SALARY_DEDUCTION_ONLY_FOR_SHORT, Map.of(),
                    "SALARY_DEDUCTION can only be applied to SHORT discrepancies. " +
                    "Use WAIVED or PENDING_INVESTIGATION for OVER discrepancies.");
        }

        if (request.resolutionAction() == DiscrepancyResolution.CASH_RECOVERY) {
            closedDayGuard.requireTodayOpen(shift.getPumpId());
            BigDecimal recovered = recordCashRecovery(shift, request.recoveredAmount(), currentUser);
            if (recovered.compareTo(shift.getDiscrepancyAmount()) < 0) {
                log.info("Part of discrepancy recovered: shiftId={}, recovered={}, remaining={}, by={}",
                        shiftId, recovered, shift.shortageNotRecovered(), currentUser.getId());
                return shiftReadModelService.toResponseWithLookups(shift);
            }
        }
        shift.setDiscrepancyResolution(request.resolutionAction());
        shift.setDiscrepancyResolutionNote(request.resolutionNote() != null ? request.resolutionNote().trim() : null);
        shift.setDiscrepancyResolvedById(currentUser.getId());
        shift.setDiscrepancyResolvedAt(businessClock.now());
        shift.setStatus(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        shift = shiftRepository.save(shift);
        if (request.resolutionAction() != DiscrepancyResolution.CASH_RECOVERY) {
            balanceSheetStalenessService.markSheetsOfShiftStale(shift.getPumpId(), shift.getShiftDate(),
                    shift.getShiftDefinitionId(), StaleReasons.discrepancyResolved(shift.getId()));
        }

        log.info("Discrepancy resolved: shiftId={}, action={}, resolvedBy={}",
                shiftId, request.resolutionAction(), currentUser.getId());

        return shiftReadModelService.toResponseWithLookups(shift);
    }

    /**
     * The operator hands back cash for a SHORT discrepancy, all of it or part. It enters the safe today; a part leaves
     * the rest pending, to be deducted from salary or waived. Only one recovery is recorded per shift.
     */
    private BigDecimal recordCashRecovery(Shift shift, BigDecimal requestedAmount, User currentUser) {
        if (shift.getCashRecoveryAmount() != null) {
            throw new BusinessException(ErrorCodes.CASH_RECOVERY_ALREADY_RECORDED,
                    Map.of("amount", shift.getCashRecoveryAmount().toPlainString()),
                    "₹" + shift.getCashRecoveryAmount() + " was already recovered for this shift. Resolve the rest by "
                            + "salary deduction or waiver.");
        }
        BigDecimal amount = (requestedAmount != null ? requestedAmount : shift.getDiscrepancyAmount())
                .setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.compareTo(shift.getDiscrepancyAmount()) > 0) {
            throw new BusinessException(ErrorCodes.CASH_RECOVERY_AMOUNT_INVALID,
                    Map.of("amount", amount.toPlainString(), "maxAmount", shift.getDiscrepancyAmount().toPlainString()),
                    "The amount recovered must be more than ₹0 and at most the shortage of ₹" + shift.getDiscrepancyAmount() + ".");
        }
        shift.setCashRecoveryAmount(amount);
        shift.setCashRecoveredAt(businessClock.now());
        shiftRepository.save(shift);
        lifecycleSupportService.createCashRecoveryEvent(shift, currentUser);
        balanceSheetStalenessService.markDaySheetsStale(shift.getPumpId(), List.of(businessClock.today()),
                StaleReasons.cashRecovered(shift.getId()));
        return amount;
    }

    private static boolean isOwnerOrAdmin(User user) {
        UserRole role = user.getRole();
        return role == UserRole.OWNER || role == UserRole.ADMIN || role == UserRole.SUPER_ADMIN;
    }

    /**
     * Records who is investigating and why; the shift stays pending until a final action is taken. Every note is kept
     * in the history; the shift shows the latest and when the investigation first started.
     */
    private ShiftResponse startInvestigation(Shift shift, String note, User currentUser) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCodes.INVESTIGATION_NOTE_REQUIRED, Map.of(),
                    "A note is required when putting a discrepancy under investigation.");
        }
        OffsetDateTime now = businessClock.now();
        investigationNoteRepository.save(ShiftInvestigationNote.builder()
                .shiftId(shift.getId())
                .note(note.trim())
                .writtenByUserId(currentUser.getId())
                .writtenAt(now)
                .build());
        shift.setInvestigationNote(note.trim());
        if (shift.getInvestigationStartedAt() == null) {
            shift.setInvestigationStartedById(currentUser.getId());
            shift.setInvestigationStartedAt(now);
        }
        shift = shiftRepository.save(shift);

        log.info("Discrepancy put under investigation: shiftId={}, status={}, by={}",
                shift.getId(), shift.getStatus(), currentUser.getId());

        return shiftReadModelService.toResponseWithLookups(shift);
    }

    /**
     * Voids a credit entry on an OPEN shift.
     */
    @Transactional
    public ShiftResponse voidCreditEntry(Long pumpId, Long shiftId, Long entryId, String voidReason, User currentUser) {
        Shift shift = findShiftForUpdate(pumpId, shiftId);

        if (!shift.getStatus().isOpen()) {
            throw new BusinessException(ErrorCodes.CREDIT_VOID_SHIFT_NOT_OPEN, Map.of(),
                    "Credit entries can only be voided while the shift is open (Business Rule 7).");
        }

        if (voidReason == null || voidReason.isBlank()) {
            throw new BusinessException(ErrorCodes.VOID_REASON_REQUIRED,
                    Map.of(),
                    "A mandatory void reason is required.");
        }

        ShiftCreditEntry entry = creditEntryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_ENTRY, "Credit entry not found"));

        if (!entry.getShiftId().equals(shiftId)) {
            throw new BusinessException(ErrorCodes.CREDIT_ENTRY_NOT_IN_SHIFT,
                    Map.of(),
                    "Credit entry does not belong to this shift.");
        }

        if ("VOIDED".equals(entry.getVoidStatus())) {
            throw new BusinessException(ErrorCodes.CREDIT_ENTRY_ALREADY_VOIDED,
                    Map.of(),
                    "Credit entry is already voided.");
        }
        closedDayGuard.requireOpen(pumpId, List.of(shift.getShiftDate(), entry.getSaleDate()));
        invoicedSaleGuard.requireFuelEntriesNotInvoiced(List.of(entryId));

        if (entry.getClientId() != null) {
            creditAccountPolicyService.lockLimitHolderOfClient(entry.getClientId());
        }

        entry.setVoidStatus("VOIDED");
        entry.setVoidReason(voidReason.trim());
        entry.setVoidedByUserId(currentUser.getId());
        entry.setVoidedAt(businessClock.now());
        creditEntryRepository.save(entry);
        if (entry.getClientId() != null) {
            creditInterestService.recalculateChargedInterestFrom(entry.getClientId(), entry.getSaleDate(),
                    "Recalculated after voiding a credit sale dated " + entry.getSaleDate(), currentUser);
        }

        log.info("Credit entry {} voided on shift {}: reason='{}', by={}", entryId, shiftId, voidReason, currentUser.getId());

        return shiftReadModelService.toResponseWithLookups(shift);
    }

    /** An operator sees only their own shifts; every other role sees all shifts of the pump. */
    @Transactional(readOnly = true)
    public List<ShiftResponse> getActiveShifts(Long pumpId, User currentUser) {
        List<Shift> shifts = shiftRepository.findActiveShiftsByPump(pumpId).stream()
                .filter(shift -> canSeeShift(currentUser, shift))
                .toList();
        return shiftReadModelService.toResponsesWithLookups(shifts);
    }

    @Transactional(readOnly = true)
    public Page<ShiftResponse> getShiftHistory(Long pumpId, ShiftHistoryFilter filter, Pageable pageable, User currentUser) {
        Page<Shift> shifts = currentUser.getRole() == UserRole.OPERATOR
                ? findOperatorHistory(pumpId, currentUser.getId(), filter, pageable)
                : findHistory(pumpId, filter, pageable);
        return new PageImpl<>(shiftReadModelService.toResponsesWithLookups(shifts.getContent()),
                shifts.getPageable(), shifts.getTotalElements());
    }

    private Page<Shift> findOperatorHistory(Long pumpId, Long operatorId, ShiftHistoryFilter filter, Pageable pageable) {
        if (filter.fuelType() != null) {
            DateRange range = fuelFilterRange(filter);
            return shiftRepository.findOperatorHistoryForFuel(pumpId, operatorId, range.from(), range.to(),
                    filter.fuelType(), pageable);
        }
        if (!filter.discrepanciesOnly() && filter.from() == null && filter.to() == null) {
            return shiftRepository.findByPumpIdAndOperatorIdOrderByActualStartTimeDesc(pumpId, operatorId, pageable);
        }
        LocalDate from = DateRangeBounds.startOrEarliest(filter.from());
        LocalDate to = DateRangeBounds.endOrLatest(filter.to());
        DateRangeBounds.requireStartNotAfterEnd(from, to);
        if (filter.discrepanciesOnly()) {
            return shiftRepository.findByPumpIdAndOperatorIdAndShiftDateBetweenAndDiscrepancyTypeInOrderByActualStartTimeDesc(
                    pumpId, operatorId, from, to, discrepancyTypesOf(filter), pageable);
        }
        return shiftRepository.findByPumpIdAndOperatorIdAndShiftDateBetweenOrderByActualStartTimeDesc(
                pumpId, operatorId, from, to, pageable);
    }

    private Page<Shift> findHistory(Long pumpId, ShiftHistoryFilter filter, Pageable pageable) {
        if (filter.fuelType() != null) {
            DateRange range = fuelFilterRange(filter);
            return shiftRepository.findHistoryForFuel(pumpId, range.from(), range.to(), filter.fuelType(), pageable);
        }
        if (!filter.discrepanciesOnly() && filter.from() == null && filter.to() == null) {
            return shiftRepository.findByPumpIdOrderByActualStartTimeDesc(pumpId, pageable);
        }
        LocalDate from = DateRangeBounds.startOrEarliest(filter.from());
        LocalDate to = DateRangeBounds.endOrLatest(filter.to());
        DateRangeBounds.requireStartNotAfterEnd(from, to);
        if (filter.discrepanciesOnly()) {
            return shiftRepository.findByPumpIdAndShiftDateBetweenAndDiscrepancyTypeInOrderByActualStartTimeDesc(
                    pumpId, from, to, discrepancyTypesOf(filter), pageable);
        }
        return shiftRepository.findByPumpIdAndShiftDateBetweenOrderByActualStartTimeDesc(pumpId, from, to, pageable);
    }

    private DateRange fuelFilterRange(ShiftHistoryFilter filter) {
        if (filter.discrepanciesOnly()) {
            throw new BusinessException(ErrorCodes.SHIFT_HISTORY_FILTER_CONFLICT,
                    Map.of(),
                    "Filter shift history by fuel or by discrepancies, not both.");
        }
        LocalDate from = DateRangeBounds.startOrEarliest(filter.from());
        LocalDate to = DateRangeBounds.endOrLatest(filter.to());
        DateRangeBounds.requireStartNotAfterEnd(from, to);
        return new DateRange(from, to);
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private List<DiscrepancyType> discrepancyTypesOf(ShiftHistoryFilter filter) {
        return filter.discrepancyType() != null
                ? List.of(filter.discrepancyType())
                : List.of(DiscrepancyType.values());
    }

    @Transactional(readOnly = true)
    public ShiftResponse getShiftById(Long pumpId, Long shiftId, User currentUser) {
        Shift shift = shiftRepository.findByIdAndPumpId(shiftId, pumpId)
                .filter(found -> canSeeShift(currentUser, found))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    private boolean canSeeShift(User currentUser, Shift shift) {
        return currentUser.getRole() != UserRole.OPERATOR || shiftOperatorService.hasWorked(shift, currentUser.getId());
    }
}
