package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Test litres: fuel a nozzle dispensed into a calibration measure and poured back into the tank. They are not sold, so
 * a reading's units sold are its meter litres less its test litres.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftTestLitresService {

    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;

    /** Rounds to 2 decimals and refuses more test litres than the meter dispensed. */
    public static BigDecimal validTestLitres(ShiftFuelReading reading, BigDecimal testLitres, BigDecimal meterLitres) {
        BigDecimal litres = testLitres == null ? BigDecimal.ZERO.setScale(2) : testLitres.setScale(2, RoundingMode.HALF_UP);
        if (litres.signum() < 0 || litres.compareTo(meterLitres) > 0) {
            throw new BusinessException(ErrorCodes.TEST_LITRES_ABOVE_METER_LITRES,
                    Map.of("testLitres", litres.toPlainString(),
                            "meterLitres", meterLitres.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            "fuelTypeCode", reading.getFuelType().name()),
                    "Test litres " + litres.toPlainString() + " for the " + reading.getFuelType() + " nozzle must be "
                            + "between 0 and the " + meterLitres.setScale(2, RoundingMode.HALF_UP).toPlainString()
                            + " litres its meter dispensed.");
        }
        return litres;
    }

    /**
     * Test litres above the pump's limit on any nozzle need a note, and the note is kept in the audit log with the
     * nozzles that went over.
     */
    public void requireNoteAndAuditAboveMax(Long pumpId, Long shiftId, Collection<ShiftFuelReading> readings, String note,
                                            User currentUser) {
        BigDecimal max = pumpLocationRepository.findById(pumpId)
                .map(PumpLocation::getMaxTestLitresPerNozzle)
                .orElse(PumpLocation.DEFAULT_MAX_TEST_LITRES_PER_NOZZLE);
        List<ShiftFuelReading> aboveMax = readings.stream()
                .filter(reading -> reading.getTestLitres().compareTo(max) > 0)
                .toList();
        if (aboveMax.isEmpty()) {
            return;
        }
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCodes.TEST_LITRES_NOTE_REQUIRED, Map.of("maxTestLitres", max.toPlainString()),
                    "Test litres above " + max.toPlainString() + " L on a nozzle need a note saying why.");
        }
        String nozzles = String.join(", ", aboveMax.stream()
                .map(reading -> reading.getFuelType() + " nozzle " + reading.getNozzleId() + ": "
                        + reading.getTestLitres().toPlainString() + " L")
                .toList());
        auditService.log(pumpId, AuditAction.TEST_LITRES_ABOVE_MAX, "Shift", shiftId.toString(),
                "Test litres above the " + max.toPlainString() + " L limit (" + nozzles + "). Note: " + note.trim(),
                currentUser);
        log.info("Test litres above limit: pump={}, shift={}, limit={}, nozzles={}, by={}",
                pumpId, shiftId, max, aboveMax.stream().map(ShiftFuelReading::getNozzleId).toList(), currentUser.getId());
    }
}
