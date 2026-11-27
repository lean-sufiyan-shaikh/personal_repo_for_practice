package com.ppms.fuel;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpShiftDefinitionRepository;
import com.ppms.shift.OpenNozzleReadingResponse;
import com.ppms.shift.ShiftPriceSegmentService;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FuelPriceService {

    private static final BigDecimal MAX_UNCONFIRMED_DEVIATION_PERCENT = new BigDecimal("15");

    private final GlobalFuelPriceRepository fuelPriceRepository;
    private final ShiftPriceSegmentService priceSegmentService;
    private final AuditService auditService;
    private final NotificationDedupService notificationDedupService;
    private final PumpShiftDefinitionRepository shiftDefinitionRepository;
    private final BusinessClock businessClock;
    private final ClosedDayGuard closedDayGuard;

    @Transactional(readOnly = true)
    public List<GlobalFuelPrice> getCurrentPrices(Long pumpId) {
        return fuelPriceRepository.findCurrentPricesForPump(pumpId);
    }

    /**
     * With a shift definition the price is the one in force when that shift started, matching what
     * backfill charges; without one it is the price at the end of the day.
     */
    @Transactional(readOnly = true)
    public List<FuelPriceForDateResponse> getPricesForDate(Long pumpId, LocalDate date, Long shiftDefinitionId) {
        ZoneId zone = businessClock.zone();
        OffsetDateTime asOf = shiftDefinitionId == null
                ? date.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
                : shiftDefinitionRepository.findByIdAndPumpId(shiftDefinitionId, pumpId)
                        .map(def -> LocalDateTime.of(date, def.getStartTime()).atZone(zone).toOffsetDateTime())
                        .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT_DEFINITION,
                                "Shift definition not found"));

        return Arrays.stream(FuelType.values())
                .map(fuelType -> FuelPriceForDateResponse.builder()
                        .fuelType(fuelType.name())
                        .pricePerUnit(fuelPriceRepository
                                .findFirstByPumpIdAndFuelTypeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                        pumpId, fuelType, asOf)
                                .map(GlobalFuelPrice::getPricePerUnit)
                                .orElse(null))
                        .build())
                .toList();
    }

    /**
     * Each price is a new record; earlier prices stay as history. A change of more than 15% from the
     * last price needs confirmed=true, otherwise PriceDeviationException (409) asks the caller to confirm.
     * A changed price splits the open shifts selling the fuel at the meter readings sent with it; re-saving the same
     * price only confirms it for today.
     */
    @Transactional
    public SetFuelPriceResponse setPrice(Long pumpId, SetFuelPriceRequest request, User currentUser) {
        closedDayGuard.requireTodayOpen(pumpId);
        requireConfirmationForLargeChange(pumpId, request);
        BigDecimal previousPrice = fuelPriceRepository
                .findFirstByPumpIdAndFuelTypeOrderByEffectiveFromDesc(pumpId, request.getFuelType())
                .map(GlobalFuelPrice::getPricePerUnit)
                .orElse(null);

        GlobalFuelPrice saved = fuelPriceRepository.save(GlobalFuelPrice.builder()
                .pumpId(pumpId)
                .fuelType(request.getFuelType())
                .pricePerUnit(request.getPricePerUnit().setScale(2, RoundingMode.HALF_UP))
                .setByUserId(currentUser.getId())
                .build());

        auditService.log(pumpId, AuditAction.FUEL_PRICE_UPDATED,
                "GlobalFuelPrice", saved.getId().toString(),
                "Fuel price updated: " + saved.getFuelType() + " → ₹" + saved.getPricePerUnit(),
                currentUser);

        List<ShiftPriceSegmentService.NozzleMeterReading> readings = request.getOpenNozzleReadings() == null ? List.of()
                : request.getOpenNozzleReadings().stream()
                        .map(reading -> new ShiftPriceSegmentService.NozzleMeterReading(reading.nozzleId(), reading.meterReading()))
                        .toList();
        boolean priceChanged = previousPrice == null || previousPrice.compareTo(saved.getPricePerUnit()) != 0;
        int splitNozzleCount = priceChanged
                ? priceSegmentService.splitOpenShiftsAtPriceChange(pumpId, saved.getFuelType(), saved.getPricePerUnit(),
                        readings, currentUser)
                : 0;
        if (splitNozzleCount > 0) {
            notifyOpenShiftsSplit(pumpId, saved, splitNozzleCount);
        }

        return SetFuelPriceResponse.builder()
                .price(saved)
                .splitNozzleCount(splitNozzleCount)
                .build();
    }

    private void requireConfirmationForLargeChange(Long pumpId, SetFuelPriceRequest request) {
        fuelPriceRepository.findFirstByPumpIdAndFuelTypeOrderByEffectiveFromDesc(pumpId, request.getFuelType())
                .map(GlobalFuelPrice::getPricePerUnit)
                .filter(lastPrice -> lastPrice.compareTo(BigDecimal.ZERO) > 0)
                .ifPresent(lastPrice -> {
                    BigDecimal newPrice = request.getPricePerUnit();
                    BigDecimal deviation = newPrice.subtract(lastPrice).abs()
                            .divide(lastPrice, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);
                    if (deviation.compareTo(MAX_UNCONFIRMED_DEVIATION_PERCENT) > 0 && !request.isConfirmed()) {
                        throw new PriceDeviationException(lastPrice, newPrice, deviation);
                    }
                });
    }

    /** One notification per pump per day; the split itself is already recorded on each shift. */
    private void notifyOpenShiftsSplit(Long pumpId, GlobalFuelPrice saved, int splitNozzleCount) {
        String dedupKey = "PRICE_CHANGE_OPEN_SHIFT:" + businessClock.today() + ":" + pumpId;
        notificationDedupService.maybeInsert(pumpId,
                NotificationType.PRICE_CHANGE_OPEN_SHIFT, dedupKey,
                "Fuel Price Changed With Open Shifts",
                saved.getFuelType() + " price updated to ₹" + saved.getPricePerUnit() + " while " + splitNozzleCount +
                " nozzle(s) were on open shifts. Litres after the meter readings taken at the change are charged at "
                        + "the new price.",
                Map.of("fuelTypeCode", saved.getFuelType().name(), "price", saved.getPricePerUnit().toPlainString(),
                        "openShiftCount", splitNozzleCount));
    }

    @Transactional(readOnly = true)
    public List<OpenNozzleReadingResponse> getOpenNozzles(Long pumpId, FuelType fuelType) {
        return priceSegmentService.openNozzlesSelling(pumpId, fuelType);
    }
}
