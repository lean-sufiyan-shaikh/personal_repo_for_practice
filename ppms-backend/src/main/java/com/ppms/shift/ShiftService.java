package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.cash.CashEvent;
import com.ppms.cash.CashEventRepository;
import com.ppms.cash.CashEventType;
import com.ppms.credit.BillingCycle;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditExtension;
import com.ppms.credit.CreditExtensionRepository;
import com.ppms.credit.CreditExtensionStatus;
import com.ppms.credit.CreditExtensionType;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.inventory.InventoryLot;
import com.ppms.inventory.InventoryLotRepository;
import com.ppms.inventory.LotConsumption;
import com.ppms.inventory.LotConsumptionRepository;
import com.ppms.inventory.LotConsumptionSource;
import com.ppms.inventory.LotStatus;
import com.ppms.pump.NozzleOutlet;
import com.ppms.pump.NozzleOutletRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.NozzleStatus;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.PumpShiftDefinition;
import com.ppms.pump.PumpShiftDefinitionService;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ShiftRepository shiftRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final NozzleRepository nozzleRepository;
    private final NozzleOutletRepository outletRepository;
    private final UserRepository userRepository;
    private final GlobalFuelPriceRepository fuelPriceRepository;
    private final InventoryLotRepository inventoryLotRepository;
    private final LotConsumptionRepository lotConsumptionRepository;
    private final UndergroundTankRepository tankRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final CreditClientRepository creditClientRepository;
    private final CreditPaymentRepository creditPaymentRepository;
    private final com.ppms.credit.CreditInterestService creditInterestService;
    private final CreditExtensionRepository creditExtensionRepository;
    private final com.ppms.planning.ShiftPlanningService shiftPlanningService;
    private final CashEventRepository cashEventRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final PumpShiftDefinitionService shiftDefinitionService;
    private final com.ppms.pump.PumpClosureRepository pumpClosureRepository;
    private final com.ppms.pump.NozzleCalibrationLogRepository calibrationLogRepository;
    private final Counter shiftOpenedCounter;
    private final Counter shiftClosedCounter;

    /**
     * Opens a new shift for a nozzle and operator.
     *
     * Rules:
     * - One open shift per nozzle at a time
     * - One open shift per operator at a time
     * - A fuel price must exist for every fuel type on the nozzle before it can open
     * - Start readings are validated against the last known reading for each outlet
     * - Start readings must be provided for every outlet on the nozzle
     */
    @Transactional
    public ShiftResponse openShift(OpenShiftRequest request, User currentUser) {
        Nozzle nozzle = nozzleRepository.findById(request.getNozzleId())
                .orElseThrow(() -> new ResourceNotFoundException("Nozzle not found"));

        // Pump closure guard: block shift creation if today is marked as a closure day
        pumpClosureRepository.findByPumpIdAndClosureDate(nozzle.getPumpId(), LocalDate.now())
                .ifPresent(closure -> {
                    throw new BusinessException(
                            "This pump is closed today (" + closure.getClosureDate() + "): " + closure.getReason() +
                            ". Remove the closure record in Setup → Closures if operations have resumed.");
                });

        // Calibration overdue guard: block shift if the nozzle's calibration has expired.
        // An expired calibration means the pump may not legally operate — shifts must not open.
        calibrationLogRepository.findLatestByNozzleId(nozzle.getId()).ifPresent(latest -> {
            if (latest.getNextCalibrationDue() != null
                    && LocalDate.now().isAfter(latest.getNextCalibrationDue())) {
                throw new BusinessException(
                        "Nozzle #" + nozzle.getNozzleNumber() + " calibration was due on " +
                        latest.getNextCalibrationDue() + " and has expired. " +
                        "Log a new calibration record (Setup → Calibration) before opening a shift.");
            }
        });

        if (nozzle.getStatus() != NozzleStatus.ACTIVE) {
            throw new BusinessException("Nozzle is inactive and cannot accept shifts");
        }

        User operator = userRepository.findById(request.getOperatorId())
                .orElseThrow(() -> new ResourceNotFoundException("Operator not found"));

        shiftRepository.findOpenShiftByNozzle(nozzle.getId()).ifPresent(existing -> {
            throw new BusinessException(
                    "Nozzle #" + nozzle.getNozzleNumber() + " already has an open shift (ID " + existing.getId() + ")");
        });

        shiftRepository.findOpenShiftByOperator(operator.getId()).ifPresent(existing -> {
            throw new BusinessException(
                    operator.getFullName() + " already has an open shift (ID " + existing.getId() + ")");
        });

        List<NozzleOutlet> outlets = outletRepository.findByNozzleId(nozzle.getId());
        if (outlets.isEmpty()) {
            throw new BusinessException("Nozzle #" + nozzle.getNozzleNumber() + " has no fuel outlets configured. " +
                    "Please add outlets in Setup before opening a shift.");
        }

        // Validate every outlet has a price and a mapped active tank.
        // Start readings come directly from outlet.lastReading — not from the request.
        // This ensures the start reading of a new shift is always exactly what the previous shift ended at.
        // If the reading looks wrong, an Admin/Owner must first correct it in Setup → Adjust Reading.
        for (NozzleOutlet outlet : outlets) {
            BigDecimal price = getLatestPrice(nozzle.getPumpId(), outlet.getFuelType());
            if (price == null) {
                throw new BusinessException("No price is set for " + outlet.getFuelType() +
                        " on this pump. Please set a price before opening a shift.");
            }
            // Tank mapping guard: each outlet must point to an active tank
            if (outlet.getTankId() == null) {
                throw new BusinessException(
                        outlet.getFuelType() + " outlet has no tank mapped. " +
                        "Please assign a tank to this outlet in Setup before opening a shift.");
            }
            tankRepository.findById(outlet.getTankId()).ifPresent(tank -> {
                if (tank.getStatus() == TankStatus.INACTIVE) {
                    throw new BusinessException(
                            "Tank '" + tank.getTankIdentifier() + "' mapped to " + outlet.getFuelType() +
                            " outlet is currently inactive. Re-enable the tank or remap the outlet before opening a shift.");
                }
            });
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Detect which shift definition covers the current time for this pump.
        // Throws BusinessException if no active definition is configured or
        // the current time falls outside all defined windows.
        PumpShiftDefinition shiftDef = shiftDefinitionService.detectCurrentShift(nozzle.getPumpId());

        Shift shift = Shift.builder()
                .pumpId(nozzle.getPumpId())
                .nozzleId(nozzle.getId())
                .operatorId(operator.getId())
                .openedByUserId(currentUser.getId())
                .shiftDefinitionId(shiftDef.getId())
                .shiftName(shiftDef.getName())
                .isNightShift(shiftDef.isNightShift())
                .shiftDate(LocalDate.now())
                .actualStartTime(now)
                .status(ShiftStatus.OPEN)
                .isOverdueFlag(false)
                .build();

        shift = shiftRepository.save(shift);
        final Long shiftId = shift.getId();

        // Persist one ShiftFuelReading per outlet, snapshotting the current price
        // and freezing the mapped tank — tank_id is captured NOW so that any
        // subsequent outlet→tank remap during the shift does not affect deduction.
        // startReading uses outlet.lastReading — the authoritative value set at previous shift close.
        for (NozzleOutlet outlet : outlets) {
            BigDecimal startReading = outlet.getLastReading();
            BigDecimal price = getLatestPrice(nozzle.getPumpId(), outlet.getFuelType());

            fuelReadingRepository.save(ShiftFuelReading.builder()
                    .shiftId(shiftId)
                    .outletId(outlet.getId())
                    .fuelType(outlet.getFuelType())
                    .tankId(outlet.getTankId())   // frozen at open
                    .startReading(startReading)
                    .priceSnapshot(price)
                    .build());
        }

        log.info("Shift {} opened: nozzle={}, operator={}, outlets={}, openedBy={}",
                shiftId, nozzle.getId(), operator.getId(), outlets.size(), currentUser.getId());
        shiftOpenedCounter.increment();

        // Reconcile shift planning: mark the actual operator as CONFIRMED,
        // any other planned operators for this slot as ABSENT.
        shiftPlanningService.reconcileOnShiftOpen(
                nozzle.getPumpId(), shift.getShiftDate(), shift.getShiftDefinitionId(), operator.getId());

        List<ShiftFuelReading> readings = fuelReadingRepository.findByShiftId(shiftId);
        return toResponse(shift, nozzle, operator, currentUser.getFullName(), readings, Collections.emptyList());
    }

    /**
     * Closes a shift.
     *
     * Steps:
     * 1. Validate end readings provided for every outlet that was opened
     * 2. Calculate units sold per fuel type (with meter rollover support)
     * 3. Compute total amount due = sum of (units * snapshotted price) across all fuel types
     * 4. Validate payment breakdown sums match total due (or flag discrepancy)
     * 5. Validate credit entries if creditTotal > 0
     * 6. FIFO inventory deduction per fuel type (scoped to pump + fuelType)
     * 7. Update outlet last_reading for pre-fill on next shift open
     */
    @Transactional
    public ShiftResponse closeShift(Long shiftId, CloseShiftRequest request, User currentUser) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN && shift.getStatus() != ShiftStatus.OPEN_OVERDUE) {
            throw new BusinessException("Shift is not open. Current status: " + shift.getStatus());
        }

        Nozzle nozzle = nozzleRepository.findById(shift.getNozzleId())
                .orElseThrow(() -> new ResourceNotFoundException("Nozzle not found"));

        List<ShiftFuelReading> readings = fuelReadingRepository.findByShiftId(shiftId);

        // Build map of outletId → end reading for O(1) lookup
        Map<Long, BigDecimal> endReadingByOutletId = request.getFuelReadings().stream()
                .collect(Collectors.toMap(
                        CloseShiftRequest.OutletEndReadingRequest::outletId,
                        CloseShiftRequest.OutletEndReadingRequest::endReading));

        // Validate every open reading has an end reading provided
        for (ShiftFuelReading reading : readings) {
            if (!endReadingByOutletId.containsKey(reading.getOutletId())) {
                throw new BusinessException("End reading missing for " + reading.getFuelType() + " outlet");
            }
        }

        // Compute units sold and total amount due
        BigDecimal totalDue = ZERO;
        for (ShiftFuelReading reading : readings) {
            BigDecimal endReading = endReadingByOutletId.get(reading.getOutletId());
            BigDecimal unitsSold = calcUnits(reading.getStartReading(), endReading, nozzle.getMaxMeterValue());

            reading.setEndReading(endReading);
            reading.setUnitsSold(unitsSold);
            fuelReadingRepository.save(reading);

            totalDue = totalDue.add(unitsSold.multiply(reading.getPriceSnapshot()));
        }
        totalDue = totalDue.setScale(2, RoundingMode.HALF_UP);

        // Total collected by operator — fleet card is cash-equivalent and counts here
        BigDecimal totalCollected = request.getCashCollected()
                .add(request.getUpiCollected())
                .add(request.getCardCollected())
                .add(request.getFleetCardCollected())
                .add(request.getCreditTotal())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal discrepancyAmount = totalCollected.subtract(totalDue).abs().setScale(2, RoundingMode.HALF_UP);
        DiscrepancyType discrepancyType = null;
        ShiftStatus newStatus = ShiftStatus.CLOSED_BALANCED;

        if (totalCollected.compareTo(totalDue) < 0) {
            discrepancyType = DiscrepancyType.SHORT;
            newStatus = ShiftStatus.CLOSED_DISCREPANCY_PENDING;
        } else if (totalCollected.compareTo(totalDue) > 0) {
            discrepancyType = DiscrepancyType.OVER;
            newStatus = ShiftStatus.CLOSED_DISCREPANCY_PENDING;
        }

        // Credit entries saved during the shift (before close) — operator may have recorded them mid-shift
        BigDecimal preExistingCreditSum = creditEntryRepository.findByShiftId(shiftId).stream()
                .map(ShiftCreditEntry::getAmount)
                .reduce(ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // New credit entries being added at close time (on top of any pre-recorded ones)
        List<CloseShiftRequest.CreditEntryRequest> creditEntries = request.getCreditEntries() == null
                ? Collections.emptyList()
                : request.getCreditEntries();

        if (request.getCreditTotal().compareTo(ZERO) > 0) {
            BigDecimal newEntriesSum = creditEntries.stream()
                    .map(CloseShiftRequest.CreditEntryRequest::amount)
                    .reduce(ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal allCreditSum = preExistingCreditSum.add(newEntriesSum).setScale(2, RoundingMode.HALF_UP);

            if (creditEntries.isEmpty() && preExistingCreditSum.compareTo(ZERO) == 0) {
                throw new BusinessException("Credit entries are required when credit total is greater than zero. " +
                        "Please add each client's name and amount.");
            }
            if (allCreditSum.compareTo(request.getCreditTotal().setScale(2, RoundingMode.HALF_UP)) != 0) {
                throw new BusinessException(
                        "Sum of all credit entries (₹" + allCreditSum + ") does not match the credit total " +
                        "(₹" + request.getCreditTotal() + "). Please check your entries.");
            }
        }

        // FIFO inventory deduction per fuel reading
        // If the reading carries a frozen tankId (shift opened after V9), deduct
        // from that tank only. For legacy readings (tankId is null) fall back to
        // the old pump+fuelType scan so historical shifts can still be closed.
        for (ShiftFuelReading reading : readings) {
            if (reading.getUnitsSold() != null && reading.getUnitsSold().compareTo(ZERO) > 0) {
                deductFromInventory(shiftId, shift.getPumpId(), reading.getFuelType(),
                        reading.getTankId(), reading.getUnitsSold());
            }
        }

        // Persist closed shift
        shift.setActualEndTime(OffsetDateTime.now());
        shift.setClosedByUserId(currentUser.getId());
        shift.setTotalAmountDue(totalDue);
        shift.setCashCollected(request.getCashCollected());
        shift.setUpiCollected(request.getUpiCollected());
        shift.setCardCollected(request.getCardCollected());
        shift.setFleetCardCollected(request.getFleetCardCollected());
        shift.setCreditTotal(request.getCreditTotal());
        shift.setDiscrepancyAmount(discrepancyAmount.compareTo(ZERO) > 0 ? discrepancyAmount : null);
        shift.setDiscrepancyType(discrepancyType);
        shift.setDiscrepancyReason(request.getDiscrepancyReason());
        shift.setStatus(newStatus);
        shift = shiftRepository.save(shift);

        log.info("Shift {} closed: totalDue={}, totalCollected={}, discrepancy={}, status={}",
                shiftId, totalDue, totalCollected, discrepancyAmount, newStatus);
        shiftClosedCounter.increment();

        // Update outlet last_reading for pre-fill on next shift open
        for (ShiftFuelReading reading : readings) {
            outletRepository.findById(reading.getOutletId()).ifPresent(outlet -> {
                outlet.setLastReading(reading.getEndReading());
                outletRepository.save(outlet);
            });
        }

        // P1.6 — Auto-create a CASH_IN event when the operator deposits cash at shift close.
        // This links the cash collection to the cash drawer ledger automatically,
        // so the manager doesn't need to manually enter the same amount in the cash drawer.
        if (request.getCashCollected().compareTo(ZERO) > 0) {
            Nozzle closedNozzle = nozzleRepository.findById(shift.getNozzleId()).orElse(null);
            String nozzleLabel = closedNozzle != null ? "Nozzle #" + closedNozzle.getNozzleNumber() : "Shift #" + shiftId;
            cashEventRepository.save(CashEvent.builder()
                    .pumpId(shift.getPumpId())
                    .eventType(CashEventType.CASH_IN)
                    .amount(request.getCashCollected().setScale(2, RoundingMode.HALF_UP))
                    .description("Cash collection from " + nozzleLabel + " shift close (Shift #" + shiftId + ")")
                    .eventDate(LocalDate.now())
                    .recordedByUserId(currentUser.getId())
                    .build());
            log.info("Auto-created CASH_IN event for shift {}: amount={}", shiftId, request.getCashCollected());
        }

        // Persist credit entries.
        // For each entry, look up the registered credit client by name so we can link the FK.
        // If the client has a credit limit set (> 0), check they won't exceed it with this shift's entries.
        final Long savedShiftId = shift.getId();
        final Long savedPumpId  = shift.getPumpId();   // capture before any reassignment enters lambda scope
        for (CloseShiftRequest.CreditEntryRequest entry : creditEntries) {
            // Prefer clientId lookup (used when sub-account is explicitly selected),
            // fall back to name-based lookup for backward compatibility.
            CreditClient matchedClient;
            if (entry.clientId() != null) {
                matchedClient = creditClientRepository.findById(entry.clientId())
                        .filter(c -> c.getPumpId().equals(savedPumpId))
                        .orElse(null);
            } else {
                matchedClient = creditClientRepository
                        .findByPumpIdAndName(shift.getPumpId(), entry.clientName().trim())
                        .orElse(null);
            }

            if (matchedClient != null) {
                // For sub-accounts: credit limit is enforced on the parent account against combined outstanding
                CreditClient limitHolder = matchedClient.getParentClientId() != null
                        ? creditClientRepository.findById(matchedClient.getParentClientId()).orElse(matchedClient)
                        : matchedClient;

                if (limitHolder.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal combinedOutstanding = creditInterestService.computeOutstandingWithChildren(limitHolder.getId());
                    BigDecimal projectedOutstanding = combinedOutstanding.add(entry.amount()).setScale(2, RoundingMode.HALF_UP);

                    if (projectedOutstanding.compareTo(limitHolder.getCreditLimit()) > 0) {
                        throw new BusinessException(
                                "Credit limit exceeded for '" + limitHolder.getName() + "'. " +
                                "Current outstanding (group): " + combinedOutstanding.setScale(2, RoundingMode.HALF_UP) +
                                ", this entry: " + entry.amount() +
                                ", limit: " + limitHolder.getCreditLimit() + ". " +
                                "Please record a payment before adding more credit.");
                    }
                }
            }

            creditEntryRepository.save(ShiftCreditEntry.builder()
                    .shiftId(savedShiftId)
                    .clientId(matchedClient != null ? matchedClient.getId() : null)
                    .clientName(entry.clientName())
                    .billNo(entry.billNo())
                    .amount(entry.amount())
                    .fuelType(entry.fuelType())
                    .description(entry.description())
                    .vehicleRegistration(entry.vehicleRegistration())
                    .driverName(entry.driverName())
                    .build());
        }

        User operator = userRepository.findById(shift.getOperatorId())
                .orElseThrow(() -> new ResourceNotFoundException("Operator not found"));
        List<ShiftCreditEntry> savedEntries = creditEntryRepository.findByShiftId(savedShiftId);
        List<ShiftFuelReading> finalReadings = fuelReadingRepository.findByShiftId(savedShiftId);
        return toResponse(shift, nozzle, operator, currentUser.getFullName(), finalReadings, savedEntries);
    }

    /**
     * Adds a credit entry to an open shift mid-shift.
     *
     * Operators can record credit sales as they happen (e.g. fleet vehicle refuels)
     * rather than trying to remember 8 hours of credit at shift close time.
     * The entry is persisted immediately so it is visible in the Credit Ledger.
     */
    @Transactional
    public ShiftResponse addCreditEntry(Long shiftId, AddCreditEntryRequest request, User currentUser) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN && shift.getStatus() != ShiftStatus.OPEN_OVERDUE) {
            throw new BusinessException("Cannot add credit entries to a shift that is not open.");
        }

        // Operators can only add credit entries to their own active shift.
        // Enforced at the service layer so it cannot be bypassed via direct API calls.
        if (currentUser.getRole() == UserRole.OPERATOR
                && !currentUser.getId().equals(shift.getOperatorId())) {
            throw new BusinessException("Operators can only add credit entries to their own active shift.");
        }

        // Prefer clientId lookup (used when sub-account is explicitly selected),
        // fall back to name-based lookup for backward compatibility.
        CreditClient matchedClient;
        if (request.getClientId() != null) {
            matchedClient = creditClientRepository.findById(request.getClientId())
                    .filter(c -> c.getPumpId().equals(shift.getPumpId()))
                    .orElse(null);
        } else {
            matchedClient = creditClientRepository
                    .findByPumpIdAndName(shift.getPumpId(), request.getClientName().trim())
                    .orElse(null);
        }

        if (matchedClient != null) {
            // For sub-accounts: credit limit and billing cycle checks use the parent account
            CreditClient limitHolder = matchedClient.getParentClientId() != null
                    ? creditClientRepository.findById(matchedClient.getParentClientId()).orElse(matchedClient)
                    : matchedClient;

            // Expire any stale extensions before reading active ones
            creditExtensionRepository.expireOverdueExtensions(LocalDate.now());
            // Extensions: check both the matched client and the parent (whichever has them)
            List<CreditExtension> activeExtensions = creditExtensionRepository
                    .findByPumpIdAndClientIdAndStatus(shift.getPumpId(), limitHolder.getId(), CreditExtensionStatus.ACTIVE);

            // Enforce credit limit on the parent's combined outstanding (Business Rule 51)
            if (limitHolder.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal combinedOutstanding = creditInterestService.computeOutstandingWithChildren(limitHolder.getId());
                BigDecimal projected = combinedOutstanding.add(request.getAmount()).setScale(2, RoundingMode.HALF_UP);

                // AMOUNT_EXTENSION: raise the effective limit by the approved extension amount
                BigDecimal extensionHeadroom = activeExtensions.stream()
                        .filter(e -> e.getExtensionType() == CreditExtensionType.AMOUNT_EXTENSION
                                && e.getExtensionAmount() != null)
                        .map(CreditExtension::getExtensionAmount)
                        .findFirst()
                        .orElse(BigDecimal.ZERO);
                BigDecimal effectiveLimit = limitHolder.getCreditLimit().add(extensionHeadroom);

                if (projected.compareTo(effectiveLimit) > 0) {
                    throw new BusinessException(
                            "Credit limit exceeded for '" + limitHolder.getName() + "'. " +
                            "Outstanding (group): " + combinedOutstanding.setScale(2, RoundingMode.HALF_UP) +
                            ", this entry: " + request.getAmount() +
                            ", effective limit: " + effectiveLimit.setScale(2, RoundingMode.HALF_UP) + ".");
                }
            }

            // Enforce billing cycle overdue block using parent's billing cycle settings
            boolean hasCycleWaiver = activeExtensions.stream().anyMatch(e ->
                    e.getExtensionType() == CreditExtensionType.BILLING_CYCLE_EXTENSION
                    || e.getExtensionType() == CreditExtensionType.OVERDUE_BLOCK_WAIVER);

            if (!hasCycleWaiver && isClientBillingCycleOverdue(limitHolder)) {
                throw new BusinessException(
                        "Credit sales blocked for '" + limitHolder.getName() + "': billing cycle overdue. " +
                        "The client must settle their outstanding balance or an Admin/Owner must grant a Credit Extension.");
            }
        }

        creditEntryRepository.save(ShiftCreditEntry.builder()
                .shiftId(shiftId)
                .clientId(matchedClient != null ? matchedClient.getId() : null)
                .clientName(request.getClientName().trim())
                .billNo(request.getBillNo() != null ? request.getBillNo().trim() : null)
                .amount(request.getAmount().setScale(2, RoundingMode.HALF_UP))
                .fuelType(request.getFuelType())
                .description(request.getDescription() != null ? request.getDescription().trim() : null)
                .vehicleRegistration(request.getVehicleRegistration() != null ? request.getVehicleRegistration().trim() : null)
                .driverName(request.getDriverName() != null ? request.getDriverName().trim() : null)
                .build());

        log.info("Credit entry added mid-shift: shiftId={}, client={}, amount={}, by={}",
                shiftId, request.getClientName(), request.getAmount(), currentUser.getId());

        return toResponseWithLookups(shift);
    }

    /**
     * Updates the discrepancy resolution for a CLOSED_DISCREPANCY_PENDING shift (spec Section 4.10).
     * WAIVED requires a non-blank reason (Business Rule 19).
     * Transitions the shift to CLOSED_DISCREPANCY_RESOLVED once a resolution is set.
     */
    @Transactional
    public ShiftResponse resolveDiscrepancy(Long shiftId, ResolveDiscrepancyRequest request, User currentUser) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.CLOSED_DISCREPANCY_PENDING) {
            throw new BusinessException(
                    "Shift is not in CLOSED_DISCREPANCY_PENDING state. Current status: " + shift.getStatus());
        }

        if (request.resolutionAction() == DiscrepancyResolution.WAIVED
                && (request.resolutionNote() == null || request.resolutionNote().isBlank())) {
            throw new BusinessException(
                    "A mandatory reason is required when waiving a discrepancy (Business Rule 19).");
        }

        // P1.5 — Escalation threshold check.
        // If the pump has a discrepancy_escalation_threshold configured, only OWNER can resolve
        // discrepancies that exceed it. ADMIN/MANAGER must escalate to the Owner.
        // Use final local copies so they are accessible inside the lambda.
        final BigDecimal discrepancyAmountForCheck = shift.getDiscrepancyAmount();
        final UserRole resolverRole = currentUser.getRole();
        if (discrepancyAmountForCheck != null) {
            pumpLocationRepository.findById(shift.getPumpId()).ifPresent(pump -> {
                BigDecimal threshold = pump.getDiscrepancyEscalationThreshold();
                if (threshold != null && threshold.compareTo(ZERO) > 0
                        && discrepancyAmountForCheck.compareTo(threshold) > 0
                        && resolverRole != UserRole.OWNER
                        && resolverRole != UserRole.SUPER_ADMIN) {
                    throw new BusinessException(
                            "Discrepancy of ₹" + discrepancyAmountForCheck + " exceeds the escalation threshold of ₹" + threshold + ". " +
                            "Only the Owner can resolve discrepancies above this amount. Please escalate to the pump Owner.");
                }
            });
        }

        shift.setDiscrepancyResolution(request.resolutionAction());
        shift.setDiscrepancyResolutionNote(request.resolutionNote() != null ? request.resolutionNote().trim() : null);
        shift.setDiscrepancyResolvedById(currentUser.getId());
        shift.setDiscrepancyResolvedAt(OffsetDateTime.now());
        shift.setStatus(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        shift = shiftRepository.save(shift);

        log.info("Discrepancy resolved: shiftId={}, action={}, resolvedBy={}",
                shiftId, request.resolutionAction(), currentUser.getId());

        return toResponseWithLookups(shift);
    }

    /**
     * Voids a credit entry on an OPEN shift (spec Section 3.7, Business Rule 7).
     * Only the operator who logged the entry or the manager may void it.
     * Voided entries stay in the DB permanently; the shift's credit_total is not recalculated
     * here — it is recalculated at shift close from non-voided entries.
     */
    @Transactional
    public ShiftResponse voidCreditEntry(Long shiftId, Long entryId, String voidReason, User currentUser) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));

        if (shift.getStatus() != ShiftStatus.OPEN && shift.getStatus() != ShiftStatus.OPEN_OVERDUE) {
            throw new BusinessException(
                    "Credit entries can only be voided while the shift is open (Business Rule 7).");
        }

        if (voidReason == null || voidReason.isBlank()) {
            throw new BusinessException("A mandatory void reason is required.");
        }

        ShiftCreditEntry entry = creditEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit entry not found"));

        if (!entry.getShiftId().equals(shiftId)) {
            throw new BusinessException("Credit entry does not belong to this shift.");
        }

        if ("VOIDED".equals(entry.getVoidStatus())) {
            throw new BusinessException("Credit entry is already voided.");
        }

        entry.setVoidStatus("VOIDED");
        entry.setVoidReason(voidReason.trim());
        entry.setVoidedByUserId(currentUser.getId());
        entry.setVoidedAt(OffsetDateTime.now());
        creditEntryRepository.save(entry);

        log.info("Credit entry {} voided on shift {}: reason='{}', by={}", entryId, shiftId, voidReason, currentUser.getId());

        return toResponseWithLookups(shift);
    }

    public List<ShiftResponse> getActiveShifts(Long pumpId) {
        return shiftRepository.findActiveShiftsByPump(pumpId).stream()
                .map(this::toResponseWithLookups)
                .toList();
    }

    public Page<ShiftResponse> getShiftHistory(Long pumpId, Pageable pageable) {
        return shiftRepository.findByPumpIdOrderByActualStartTimeDesc(pumpId, pageable)
                .map(this::toResponseWithLookups);
    }

    public ShiftResponse getShiftById(Long shiftId) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        return toResponseWithLookups(shift);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FIFO inventory deduction.
     *
     * When tankId is non-null (shift opened after V9 migration), deduction is
     * scoped to that specific tank — the outlet's mapped tank was frozen at
     * shift-open. This accurately reflects physical pipe connections.
     *
     * When tankId is null (pre-V9 legacy shift), falls back to the original
     * pump+fuelType scan across all active tanks so old shifts can still close.
     *
     * Consumes from oldest lots first. Creates a LotConsumption record for each
     * lot touched. Also decrements each tank's current_stock proportionally.
     * Negative stock is allowed — it surfaces as a DIP discrepancy alert.
     */
    private void deductFromInventory(Long shiftId, Long pumpId, FuelType fuelType,
                                     Long tankId, BigDecimal unitsToDeduct) {
        List<InventoryLot> lots = tankId != null
                ? inventoryLotRepository.findActiveLotsByTankFifo(tankId)
                : inventoryLotRepository.findActiveLotsByPumpAndFuelTypeFifo(pumpId, fuelType);
        BigDecimal remaining = unitsToDeduct;

        for (InventoryLot lot : lots) {
            if (remaining.compareTo(ZERO) <= 0) break;

            BigDecimal consume = remaining.min(lot.getRemainingQuantity());
            lot.setRemainingQuantity(lot.getRemainingQuantity().subtract(consume).setScale(3, RoundingMode.HALF_UP));

            if (lot.getRemainingQuantity().compareTo(ZERO) == 0) {
                lot.setStatus(LotStatus.EXHAUSTED);
            }
            inventoryLotRepository.save(lot);

            lotConsumptionRepository.save(LotConsumption.builder()
                    .lotId(lot.getId())
                    .sourceType(LotConsumptionSource.SHIFT_CLOSE)
                    .shiftId(shiftId)
                    .quantityConsumed(consume)
                    .costPricePerUnit(lot.getCostPricePerUnit())
                    .build());

            // Decrement the physical tank's stock
            tankRepository.findById(lot.getTankId()).ifPresent(tank -> {
                BigDecimal newStock = tank.getCurrentStock().subtract(consume).setScale(3, RoundingMode.HALF_UP);
                tank.setCurrentStock(newStock);
                tankRepository.save(tank);
            });

            remaining = remaining.subtract(consume);
        }

        // If stock ran out before all units were deducted — allow negative (DIP will surface it)
        if (remaining.compareTo(ZERO) > 0) {
            log.warn("Inventory shortage for pump={} fuelType={} tankId={}: {} L deducted but {} L not covered by any lot",
                    pumpId, fuelType, tankId, unitsToDeduct, remaining);
        }
    }

    /**
     * Returns true if the client has an outstanding balance that has exceeded their billing cycle deadline.
     *
     * Logic:
     * - WEEKLY: overdue if outstanding > 0 and no payment in the last 7 days
     * - MONTHLY: overdue if outstanding > 0 and no payment in the last 30 days
     * - If the client has never made a payment, the oldest credit entry date is used as the anchor.
     *
     * A return value of true will cause addCreditEntry() to block the sale unless an active
     * BILLING_CYCLE_EXTENSION or OVERDUE_BLOCK_WAIVER extension is in place.
     */
    private boolean isClientBillingCycleOverdue(CreditClient client) {
        BigDecimal outstanding = creditInterestService.computeOutstanding(client.getId());
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return false; // Nothing owed — client is current
        }

        int cycleDays = client.getBillingCycle() == BillingCycle.WEEKLY ? 7 : 30;
        LocalDate overdueThreshold = LocalDate.now().minusDays(cycleDays);

        Optional<LocalDate> lastPayment = creditPaymentRepository.findLastPaymentDateByClientId(client.getId());
        if (lastPayment.isPresent()) {
            // Client is overdue if their last payment falls outside the current billing window
            return lastPayment.get().isBefore(overdueThreshold);
        }

        // No payments at all — use oldest credit entry date as reference
        return creditEntryRepository.findOldestEntryDateByClientId(client.getId())
                .map(dt -> dt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().isBefore(overdueThreshold))
                .orElse(false); // No entries either — not overdue
    }

    private BigDecimal calcUnits(BigDecimal start, BigDecimal end, BigDecimal maxMeterValue) {
        if (end.compareTo(start) >= 0) {
            return end.subtract(start).setScale(3, RoundingMode.HALF_UP);
        }
        // Meter rollover: (max - start) + end
        return maxMeterValue.subtract(start).add(end).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal getLatestPrice(Long pumpId, FuelType fuelType) {
        return fuelPriceRepository
                .findFirstByPumpIdAndFuelTypeOrderByEffectiveFromDesc(pumpId, fuelType)
                .map(p -> p.getPricePerUnit())
                .orElse(null);
    }

    private ShiftResponse toResponseWithLookups(Shift shift) {
        Nozzle nozzle = nozzleRepository.findById(shift.getNozzleId()).orElse(null);
        User operator = userRepository.findById(shift.getOperatorId()).orElse(null);
        String openedByName = userRepository.findById(shift.getOpenedByUserId())
                .map(User::getFullName).orElse(null);
        List<ShiftFuelReading> readings = fuelReadingRepository.findByShiftId(shift.getId());
        List<ShiftCreditEntry> entries = creditEntryRepository.findByShiftId(shift.getId());
        return toResponse(shift, nozzle, operator, openedByName, readings, entries);
    }

    private ShiftResponse toResponse(Shift shift, Nozzle nozzle, User operator, String openedByName,
                                     List<ShiftFuelReading> readings, List<ShiftCreditEntry> entries) {

        List<ShiftResponse.FuelReadingResponse> fuelReadingResponses = readings.stream()
                .map(r -> new ShiftResponse.FuelReadingResponse(
                        r.getOutletId(),
                        r.getFuelType(),
                        r.getStartReading(),
                        r.getEndReading(),
                        r.getPriceSnapshot(),
                        r.getUnitsSold()))
                .toList();

        List<ShiftResponse.CreditEntryResponse> creditEntryResponses = entries.stream()
                .map(e -> new ShiftResponse.CreditEntryResponse(
                        e.getId(), e.getClientName(), e.getBillNo(), e.getAmount(),
                        e.getFuelType(), e.getDescription(),
                        e.getVoidStatus(), e.getVoidReason(),
                        e.getVehicleRegistration(), e.getDriverName()))
                .toList();

        return ShiftResponse.builder()
                .id(shift.getId())
                .pumpId(shift.getPumpId())
                .nozzleId(shift.getNozzleId())
                .nozzleNumber(nozzle != null ? nozzle.getNozzleNumber() : null)
                .operatorId(shift.getOperatorId())
                .operatorName(operator != null ? operator.getFullName() : null)
                .openedByUserName(openedByName)
                .shiftWindow(shift.getShiftName())
                .shiftDate(shift.getShiftDate())
                .actualStartTime(shift.getActualStartTime())
                .actualEndTime(shift.getActualEndTime())
                .fuelReadings(fuelReadingResponses)
                .totalAmountDue(shift.getTotalAmountDue())
                .cashCollected(shift.getCashCollected())
                .upiCollected(shift.getUpiCollected())
                .cardCollected(shift.getCardCollected())
                .fleetCardCollected(shift.getFleetCardCollected())
                .creditTotal(shift.getCreditTotal())
                .discrepancyAmount(shift.getDiscrepancyAmount())
                .discrepancyType(shift.getDiscrepancyType() != null ? shift.getDiscrepancyType().name() : null)
                .discrepancyReason(shift.getDiscrepancyReason())
                .discrepancyResolution(shift.getDiscrepancyResolution() != null ? shift.getDiscrepancyResolution().name() : null)
                .discrepancyResolutionNote(shift.getDiscrepancyResolutionNote())
                .status(shift.getStatus().name())
                .creditEntries(creditEntryResponses)
                .build();
    }
}
