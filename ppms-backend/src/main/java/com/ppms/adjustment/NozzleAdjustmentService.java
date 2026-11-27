package com.ppms.adjustment;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.inventory.LotConsumption;
import com.ppms.inventory.LotConsumptionRepository;
import com.ppms.inventory.LotConsumptionSource;
import com.ppms.inventory.TankLotConsumptionService;
import com.ppms.pump.Nozzle;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NozzleAdjustmentService {

    private final NozzleReadingAdjustmentRepository adjustmentRepository;
    private final FuelDipEntryRepository            dipEntryRepository;
    private final NozzleAdjustmentSupportService    supportService;
    private final BusinessClock businessClock;
    private final UndergroundTankRepository tankRepository;
    private final TankLotConsumptionService tankLotConsumptionService;
    private final LotConsumptionRepository lotConsumptionRepository;
    private final BalanceSheetStalenessService balanceSheetStalenessService;
    private final ClosedDayGuard closedDayGuard;
    private final GlobalFuelPriceRepository fuelPriceRepository;
    private final AuditService auditService;

    // ── Meter reading adjustment ──────────────────────────────────────────────

    /**
     * Records a meter reading adjustment (RESET or CUSTOM_READING) on a nozzle with no shift running on it; the
     * nozzle row is locked for the whole adjustment.
     *
     * Moving the meter back is an Owner/Admin correction and is applied at once. Moving it forward means fuel left
     * the nozzle without a sale: the gap is recorded as unbilled litres valued at today's price and drawn from the
     * nozzle's tank as a drain. An Owner/Admin applies it at once; a manager's request waits for one to approve it.
     */
    @Transactional
    public NozzleReadingAdjustment recordReadingAdjustment(
            Long pumpId, Long nozzleId, RecordAdjustmentRequest req, User currentUser) {

        closedDayGuard.requireTodayOpen(pumpId);
        Nozzle nozzle = supportService.requireAdjustableNozzle(pumpId, nozzleId);
        String type = supportService.validateAdjustmentType(req.getAdjustmentType());

        BigDecimal previousReading = nozzle.getLastReading();
        BigDecimal newReading = supportService.resolveNewReading(type, req.getNewReading());
        boolean forward = newReading.compareTo(previousReading) > 0;
        boolean ownerOrAdmin = isOwnerOrAdmin(currentUser);
        if (!forward && !ownerOrAdmin) {
            throw new BusinessException(ErrorCodes.ADJUSTMENT_OWNER_OR_ADMIN_ONLY, Map.of(),
                    "Only Owner or Admin can record reading adjustments and dip entries.");
        }

        NozzleReadingAdjustment adjustment = NozzleReadingAdjustment.builder()
                .pumpId(pumpId)
                .nozzleId(nozzleId)
                .adjustmentType(type)
                .fuelType(nozzle.getFuelType().name())
                .previousReading(previousReading)
                .newReading(newReading)
                .reason(req.getReason().trim())
                .recordedByUserId(currentUser.getId())
                .build();

        if (!forward) {
            moveMeter(pumpId, nozzle, adjustment);
            NozzleReadingAdjustment saved = adjustmentRepository.save(adjustment);
            auditService.log(pumpId, AuditAction.METER_READING_ADJUSTED, "NozzleReadingAdjustment",
                    saved.getId().toString(), "Nozzle " + nozzleId + " meter moved back " + previousReading + " → "
                            + newReading + " (" + type + "), reason=" + saved.getReason(), currentUser);
            return saved;
        }

        if (adjustmentRepository.existsByNozzleIdAndStatus(nozzleId, MeterAdjustmentStatus.PENDING_APPROVAL)) {
            throw new BusinessException(ErrorCodes.METER_ADJUSTMENT_ALREADY_PENDING, Map.of(),
                    "A meter adjustment for this nozzle is already waiting for approval.");
        }
        valueUnbilledLitres(pumpId, nozzle, adjustment);
        if (!ownerOrAdmin) {
            adjustment.setStatus(MeterAdjustmentStatus.PENDING_APPROVAL);
            NozzleReadingAdjustment saved = adjustmentRepository.save(adjustment);
            auditService.log(pumpId, AuditAction.METER_ADJUSTMENT_REQUESTED, "NozzleReadingAdjustment",
                    saved.getId().toString(), describeForward(saved), currentUser);
            log.info("Meter adjustment requested: nozzle={}, {} → {}, unbilled={} L, by={}",
                    nozzleId, previousReading, newReading, saved.getUnbilledLitres(), currentUser.getId());
            return saved;
        }

        applyForward(pumpId, nozzle, adjustment, currentUser);
        NozzleReadingAdjustment saved = adjustmentRepository.save(adjustment);
        auditService.log(pumpId, AuditAction.METER_READING_ADJUSTED, "NozzleReadingAdjustment",
                saved.getId().toString(), describeForward(saved), currentUser);
        return saved;
    }

    /**
     * An Owner or Admin approves or rejects a manager's forward adjustment. Approval applies it as if the reviewer
     * had recorded it, provided the nozzle still shows the reading the request started from.
     */
    @Transactional
    public void review(Long pumpId, Long adjustmentId, ExpenseApprovalStatus action, String reason, User reviewer) {
        NozzleReadingAdjustment adjustment = adjustmentRepository.findByIdForUpdate(adjustmentId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.NOZZLE, "Meter adjustment not found"));
        if (adjustment.getStatus() != MeterAdjustmentStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCodes.METER_ADJUSTMENT_NOT_PENDING,
                    Map.of("statusCode", adjustment.getStatus().name()),
                    "This meter adjustment is not waiting for approval (it is " + adjustment.getStatus() + ").");
        }
        if (!isOwnerOrAdmin(reviewer)) {
            throw new BusinessException(ErrorCodes.ADJUSTMENT_OWNER_OR_ADMIN_ONLY, Map.of(),
                    "Only Owner or Admin can record reading adjustments and dip entries.");
        }
        String reviewReason = reason == null || reason.isBlank() ? null : reason.trim();

        if (action == ExpenseApprovalStatus.REJECTED) {
            adjustment.setStatus(MeterAdjustmentStatus.REJECTED);
            adjustment.setReviewedByUserId(reviewer.getId());
            adjustment.setReviewedAt(businessClock.now());
            adjustment.setReviewReason(reviewReason);
            adjustmentRepository.save(adjustment);
            auditService.log(pumpId, AuditAction.METER_ADJUSTMENT_REJECTED, "NozzleReadingAdjustment",
                    adjustmentId.toString(), describeForward(adjustment) + ", rejected: " + reviewReason, reviewer);
            return;
        }

        closedDayGuard.requireTodayOpen(pumpId);
        Nozzle nozzle = supportService.requireAdjustableNozzle(pumpId, adjustment.getNozzleId());
        if (nozzle.getLastReading().compareTo(adjustment.getPreviousReading()) != 0) {
            throw new BusinessException(ErrorCodes.METER_ADJUSTMENT_READING_MOVED,
                    Map.of("expectedReading", adjustment.getPreviousReading().toPlainString(),
                            "currentReading", nozzle.getLastReading().toPlainString()),
                    "The nozzle's reading changed from " + adjustment.getPreviousReading().toPlainString() + " to "
                            + nozzle.getLastReading().toPlainString()
                            + " since this adjustment was asked for. Ask for a new adjustment.");
        }
        valueUnbilledLitres(pumpId, nozzle, adjustment);
        applyForward(pumpId, nozzle, adjustment, reviewer);
        adjustment.setReviewReason(reviewReason);
        adjustmentRepository.save(adjustment);
        auditService.log(pumpId, AuditAction.METER_ADJUSTMENT_APPROVED, "NozzleReadingAdjustment",
                adjustmentId.toString(), describeForward(adjustment), reviewer);
    }

    public List<NozzleReadingAdjustment> pendingAdjustments(Long pumpId) {
        return adjustmentRepository.findByPumpIdAndStatusOrderByCreatedAtAsc(pumpId, MeterAdjustmentStatus.PENDING_APPROVAL);
    }

    private void valueUnbilledLitres(Long pumpId, Nozzle nozzle, NozzleReadingAdjustment adjustment) {
        BigDecimal litres = adjustment.getNewReading().subtract(adjustment.getPreviousReading())
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal price = fuelPriceRepository.findCurrentPricesForPump(pumpId).stream()
                .filter(current -> current.getFuelType() == nozzle.getFuelType())
                .map(GlobalFuelPrice::getPricePerUnit)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCodes.METER_ADJUSTMENT_NO_PRICE,
                        Map.of("fuelTypeCode", nozzle.getFuelType().name()),
                        "There is no current price for " + nozzle.getFuelType()
                                + ", so the unbilled litres cannot be valued."));
        adjustment.setUnbilledLitres(litres);
        adjustment.setPricePerLitre(price);
        adjustment.setUnbilledValue(litres.multiply(price).setScale(2, RoundingMode.HALF_UP));
    }

    /** Draws the unbilled litres from the nozzle's tank as a drain, so stock and wet-stock checks stay right. */
    private void applyForward(Long pumpId, Nozzle nozzle, NozzleReadingAdjustment adjustment, User approver) {
        if (nozzle.getTankId() == null) {
            throw new BusinessException(ErrorCodes.METER_ADJUSTMENT_NO_TANK, Map.of(),
                    "This nozzle is not linked to a tank, so its unbilled litres cannot be taken from stock.");
        }
        UndergroundTank tank = tankRepository.findByIdForUpdate(nozzle.getTankId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.TANK, "Tank not found"));
        FuelDipEntry drain = drainFromTank(pumpId, tank, adjustment.getUnbilledLitres(),
                "Unbilled meter gap on nozzle " + nozzle.getId() + ": " + adjustment.getReason(), approver);
        adjustment.setFuelDipEntryId(drain.getId());
        adjustment.setStatus(MeterAdjustmentStatus.APPLIED);
        adjustment.setReviewedByUserId(approver.getId());
        adjustment.setReviewedAt(businessClock.now());
        moveMeter(pumpId, nozzle, adjustment);
    }

    /** The nozzle's last reading becomes the start reading of its next shift. */
    private void moveMeter(Long pumpId, Nozzle nozzle, NozzleReadingAdjustment adjustment) {
        nozzle.setLastReading(adjustment.getNewReading());
        supportService.saveNozzle(nozzle);
        balanceSheetStalenessService.markDaySheetsStale(pumpId, List.of(businessClock.today()),
                StaleReasons.meterAmended(nozzle.getId()));
        log.info("Meter reading adjusted: nozzle={}, type={}, {} → {}, unbilled={} L",
                nozzle.getId(), adjustment.getAdjustmentType(), adjustment.getPreviousReading(),
                adjustment.getNewReading(), adjustment.getUnbilledLitres());
    }

    private static String describeForward(NozzleReadingAdjustment adjustment) {
        return "Nozzle " + adjustment.getNozzleId() + " meter moved forward " + adjustment.getPreviousReading() + " → "
                + adjustment.getNewReading() + ": " + adjustment.getUnbilledLitres() + " L unbilled worth ₹"
                + adjustment.getUnbilledValue() + " at ₹" + adjustment.getPricePerLitre() + "/L, reason="
                + adjustment.getReason();
    }

    private static boolean isOwnerOrAdmin(User user) {
        return user.getRole() == UserRole.OWNER || user.getRole() == UserRole.ADMIN;
    }

    public List<NozzleReadingAdjustment> getAdjustments(Long pumpId, Long nozzleId) {
        supportService.requireNozzleForPump(pumpId, nozzleId);
        return adjustmentRepository.findByNozzleIdOrderByCreatedAtDesc(nozzleId);
    }

    // ── Fuel dip entry ────────────────────────────────────────────────────────

    /**
     * Records a fuel dip entry — physical fuel removed from the tank for maintenance or testing.
     *
     * The litres leave the tank's stock and are drawn from its oldest lots, so a later dip check does not count
     * them as a loss again. The monetary loss is the purchase cost of those lots and is subtracted from gross
     * profit when generating balance sheets for the dip_date.
     *
     * Only today's drain can be recorded, since it reduces today's stock; no shift state dependency.
     */
    @Transactional
    public FuelDipEntry recordDip(Long pumpId, RecordDipRequest req, User currentUser) {
        UndergroundTank tank = tankRepository.findByIdForUpdate(req.getTankId())
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.TANK, "Tank not found"));

        LocalDate dipDate = businessClock.today();
        if (req.getDipDate() != null && !req.getDipDate().equals(dipDate)) {
            throw new BusinessException(ErrorCodes.DRAIN_ONLY_TODAY,
                    Map.of("today", dipDate.toString(), "enteredDate", req.getDipDate().toString()),
                    "A drain can only be recorded for today (" + dipDate + "). It takes the fuel out of today's tank " +
                    "stock, so one dated " + req.getDipDate() + " would put the loss on the wrong day.");
        }

        closedDayGuard.requireOpen(pumpId, dipDate);

        return drainFromTank(pumpId, tank, req.getLitresRemoved().setScale(3, RoundingMode.HALF_UP),
                req.getReason().trim(), currentUser);
    }

    /**
     * Takes litres out of a locked tank for today, drawn from its oldest lots; the loss is the purchase cost of those
     * lots and is subtracted from gross profit on the day's balance sheets.
     */
    private FuelDipEntry drainFromTank(Long pumpId, UndergroundTank tank, BigDecimal litresRemoved, String reason,
                                       User currentUser) {
        LocalDate dipDate = businessClock.today();
        BigDecimal stock = tank.getCurrentStock() != null ? tank.getCurrentStock() : BigDecimal.ZERO;
        if (litresRemoved.compareTo(stock) > 0) {
            throw new BusinessException(ErrorCodes.DRAIN_EXCEEDS_TANK_STOCK, Map.of("tankName", tank.getTankIdentifier(),
                            "stock", stock.setScale(3, RoundingMode.HALF_UP).toPlainString(), "litresRemoved", litresRemoved.toPlainString()),
                    "Tank '" + tank.getTankIdentifier() + "' holds only " + stock.setScale(3, RoundingMode.HALF_UP) +
                    " L, so " + litresRemoved + " L cannot be removed from it.");
        }

        List<LotConsumption> draws = tankLotConsumptionService.consumeOldestFirst(
                tank, litresRemoved, LotConsumptionSource.MAINTENANCE_REMOVAL);
        BigDecimal lotCost = draws.stream()
                .map(draw -> draw.getQuantityConsumed().multiply(draw.getCostPricePerUnit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal monetaryLoss = lotCost.setScale(2, RoundingMode.HALF_UP);
        BigDecimal costPerLitre = lotCost.divide(litresRemoved, 3, RoundingMode.HALF_UP);

        tank.setCurrentStock(stock.subtract(litresRemoved).setScale(3, RoundingMode.HALF_UP));
        tankRepository.save(tank);

        FuelDipEntry saved = dipEntryRepository.save(FuelDipEntry.builder()
                .pumpId(pumpId)
                .tankId(tank.getId())
                .fuelType(tank.getFuelType().name())
                .litresRemoved(litresRemoved)
                .pricePerUnit(costPerLitre)
                .monetaryLoss(monetaryLoss)
                .reason(reason)
                .dipDate(dipDate)
                .recordedByUserId(currentUser.getId())
                .build());
        draws.forEach(draw -> draw.setFuelDipEntryId(saved.getId()));
        lotConsumptionRepository.saveAll(draws);
        balanceSheetStalenessService.markAllSheetsStale(pumpId, List.of(dipDate),
                StaleReasons.drainRecorded(litresRemoved.toPlainString()));

        log.info("Fuel dip recorded: pump={}, tank={}, fuel={}, litres={}, loss={}, date={}, by={}",
                pumpId, tank.getId(), tank.getFuelType(), litresRemoved, monetaryLoss, dipDate, currentUser.getId());

        return saved;
    }

    public List<FuelDipEntry> getDips(Long pumpId) {
        return dipEntryRepository.findByPumpIdOrderByDipDateDescCreatedAtDesc(pumpId);
    }
}
