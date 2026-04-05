package com.ppms.adjustment;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.pump.NozzleOutlet;
import com.ppms.pump.NozzleOutletRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NozzleAdjustmentService {

    private final NozzleReadingAdjustmentRepository adjustmentRepository;
    private final FuelDipEntryRepository            dipEntryRepository;
    private final NozzleOutletRepository            outletRepository;
    private final ShiftRepository                   shiftRepository;
    private final GlobalFuelPriceRepository         fuelPriceRepository;

    // ── Meter reading adjustment ──────────────────────────────────────────────

    /**
     * Records a meter reading adjustment (RESET or CUSTOM_READING) on a nozzle outlet.
     *
     * Rules:
     * - Only allowed when the nozzle has no active shift (OPEN / OPEN_OVERDUE).
     *   An active shift uses the outlet's lastReading as its start reading snapshot;
     *   changing it mid-shift would corrupt the units-sold calculation.
     * - RESET sets lastReading to 0 (meter physically reset to zero counter).
     * - CUSTOM_READING sets lastReading to the provided newReading value.
     *
     * No financial impact — this is purely an audit log + reading correction.
     */
    @Transactional
    public NozzleReadingAdjustment recordReadingAdjustment(
            Long pumpId, Long outletId, RecordAdjustmentRequest req, User currentUser) {

        NozzleOutlet outlet = outletRepository.findById(outletId)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet not found"));

        // Guard: cannot adjust while an active shift is running on this nozzle
        if (shiftRepository.hasOpenShift(outlet.getNozzleId())) {
            throw new BusinessException(
                    "Cannot adjust the meter reading — an active shift is running on this nozzle. " +
                    "Close the shift first.");
        }

        String type = req.getAdjustmentType().toUpperCase();
        if (!type.equals("RESET") && !type.equals("CUSTOM_READING")) {
            throw new BusinessException("adjustmentType must be RESET or CUSTOM_READING");
        }

        BigDecimal previousReading = outlet.getLastReading();
        BigDecimal newReading;

        if (type.equals("RESET")) {
            newReading = BigDecimal.ZERO;
        } else {
            if (req.getNewReading() == null) {
                throw new BusinessException("newReading is required for CUSTOM_READING adjustment");
            }
            if (req.getNewReading().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("newReading cannot be negative");
            }
            newReading = req.getNewReading().setScale(3, RoundingMode.HALF_UP);
        }

        // Update the outlet's lastReading — this becomes the start reading for the next shift
        outlet.setLastReading(newReading);
        outletRepository.save(outlet);

        NozzleReadingAdjustment adjustment = NozzleReadingAdjustment.builder()
                .pumpId(pumpId)
                .nozzleId(outlet.getNozzleId())
                .outletId(outletId)
                .adjustmentType(type)
                .fuelType(outlet.getFuelType().name())
                .previousReading(previousReading)
                .newReading(newReading)
                .reason(req.getReason().trim())
                .recordedByUserId(currentUser.getId())
                .build();

        NozzleReadingAdjustment saved = adjustmentRepository.save(adjustment);

        log.info("Meter reading adjusted: outlet={}, nozzle={}, type={}, {} → {}, by={}",
                outletId, outlet.getNozzleId(), type, previousReading, newReading, currentUser.getId());

        return saved;
    }

    public List<NozzleReadingAdjustment> getAdjustments(Long outletId) {
        return adjustmentRepository.findByOutletIdOrderByCreatedAtDesc(outletId);
    }

    // ── Fuel dip entry ────────────────────────────────────────────────────────

    /**
     * Records a fuel dip entry — physical fuel removed from the tank for maintenance or testing.
     *
     * The monetary loss is calculated as: litresRemoved × current fuel price (snapshotted at creation time).
     * This loss is subtracted from gross profit when generating balance sheets for the dip_date.
     *
     * Can be recorded at any time (no shift state dependency) by Admin/Owner.
     */
    @Transactional
    public FuelDipEntry recordDip(Long pumpId, RecordDipRequest req, User currentUser) {
        FuelType fuelType;
        try {
            fuelType = FuelType.valueOf(req.getFuelType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unknown fuel type: " + req.getFuelType());
        }

        // Snapshot current price at the time of recording for historical accuracy
        BigDecimal pricePerUnit = fuelPriceRepository
                .findFirstByPumpIdAndFuelTypeOrderByEffectiveFromDesc(pumpId, fuelType)
                .map(p -> p.getPricePerUnit())
                .orElseThrow(() -> new BusinessException(
                        "No price configured for " + fuelType + " on this pump. Set a price first."));

        BigDecimal litresRemoved = req.getLitresRemoved().setScale(3, RoundingMode.HALF_UP);
        BigDecimal monetaryLoss  = litresRemoved.multiply(pricePerUnit).setScale(2, RoundingMode.HALF_UP);
        LocalDate  dipDate       = req.getDipDate() != null ? req.getDipDate() : LocalDate.now();

        FuelDipEntry entry = FuelDipEntry.builder()
                .pumpId(pumpId)
                .fuelType(fuelType.name())
                .litresRemoved(litresRemoved)
                .pricePerUnit(pricePerUnit)
                .monetaryLoss(monetaryLoss)
                .reason(req.getReason().trim())
                .dipDate(dipDate)
                .recordedByUserId(currentUser.getId())
                .build();

        FuelDipEntry saved = dipEntryRepository.save(entry);

        log.info("Fuel dip recorded: pump={}, fuel={}, litres={}, loss={}, date={}, by={}",
                pumpId, fuelType, litresRemoved, monetaryLoss, dipDate, currentUser.getId());

        return saved;
    }

    public List<FuelDipEntry> getDips(Long pumpId) {
        return dipEntryRepository.findByPumpIdOrderByDipDateDescCreatedAtDesc(pumpId);
    }
}
