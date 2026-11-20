package com.ppms.fuel;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpShiftDefinitionRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/pumps/{pumpId}/fuel-prices")
@RequiredArgsConstructor
public class FuelPriceController {

    private final GlobalFuelPriceRepository fuelPriceRepository;
    private final ShiftRepository shiftRepository;
    private final AuditService auditService;
    private final NotificationDedupService notificationDedupService;
    private final PumpShiftDefinitionRepository shiftDefinitionRepository;
    private final BusinessClock businessClock;

    /**
     * GET /api/pumps/{pumpId}/fuel-prices/current
     * Returns the most recently set price for each fuel type at a pump.
     * Accessible by all operational roles — operators need the current price to open a shift.
     */
    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR', 'ACCOUNTANT')")
    public ResponseEntity<List<GlobalFuelPrice>> getCurrentPrices(@PathVariable Long pumpId) {
        return ResponseEntity.ok(fuelPriceRepository.findCurrentPricesForPump(pumpId));
    }

    /**
     * GET /api/pumps/{pumpId}/fuel-prices/for-date?date=YYYY-MM-DD[&shiftDefinitionId=N]
     * Returns the price that was effective for each fuel type at a pump on the given historical date.
     * With a shift definition, the price is the one in force when that shift started, matching what
     * backfill charges; without one, it is the price at the end of the day.
     * pricePerUnit is null when no price record exists by then for a fuel type.
     */
    @GetMapping("/for-date")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<List<FuelPriceForDateResponse>> getPricesForDate(
            @PathVariable Long pumpId,
            @RequestParam LocalDate date,
            @RequestParam(required = false) Long shiftDefinitionId) {

        ZoneId zone = businessClock.zone();
        OffsetDateTime asOf = shiftDefinitionId == null
                ? date.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
                : shiftDefinitionRepository.findByIdAndPumpId(shiftDefinitionId, pumpId)
                        .map(def -> LocalDateTime.of(date, def.getStartTime()).atZone(zone).toOffsetDateTime())
                        .orElseThrow(() -> new ResourceNotFoundException("Shift definition not found"));

        List<FuelPriceForDateResponse> responses = Arrays.stream(FuelType.values())
                .map(fuelType -> {
                    Optional<GlobalFuelPrice> price =
                            fuelPriceRepository.findFirstByPumpIdAndFuelTypeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                                    pumpId, fuelType, asOf);
                    return FuelPriceForDateResponse.builder()
                            .fuelType(fuelType.name())
                            .pricePerUnit(price.map(GlobalFuelPrice::getPricePerUnit).orElse(null))
                            .build();
                })
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * POST /api/pumps/{pumpId}/fuel-prices
     * Sets (or updates) a fuel price for a pump.
     * Creates a new immutable price record — previous prices are retained as history (spec Rule 15).
     *
     * 15% deviation guard (Business Rule 37, Section 6.12):
     * If the new price deviates >15% from the last recorded price and confirmed=false,
     * returns HTTP 409 with a PriceDeviationWarning. The frontend must show a confirmation
     * dialog and re-submit with confirmed=true to bypass the guard.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<?> setPrice(
            @PathVariable Long pumpId,
            @Valid @RequestBody SetFuelPriceRequest request,
            @AuthenticationPrincipal User currentUser) {

        // Check for >15% deviation against the last recorded price for this fuel type at this pump
        fuelPriceRepository.findFirstByPumpIdAndFuelTypeOrderByEffectiveFromDesc(
                pumpId, request.getFuelType()).ifPresent(last -> {

            BigDecimal lastPrice = last.getPricePerUnit();
            BigDecimal newPrice  = request.getPricePerUnit();

            if (lastPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deviation = newPrice.subtract(lastPrice).abs()
                        .divide(lastPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

                if (deviation.compareTo(new BigDecimal("15")) > 0 && !request.isConfirmed()) {
                    // Signal to the caller that confirmation is needed — throw a checked-style exception
                    // that the controller catches and converts to 409.
                    throw new PriceDeviationException(lastPrice, newPrice, deviation);
                }
            }
        });

        GlobalFuelPrice price = GlobalFuelPrice.builder()
                .pumpId(pumpId)
                .fuelType(request.getFuelType())
                .pricePerUnit(request.getPricePerUnit().setScale(2, RoundingMode.HALF_UP))
                .setByUserId(currentUser.getId())
                .build();

        GlobalFuelPrice saved = fuelPriceRepository.save(price);

        auditService.log(pumpId, AuditAction.FUEL_PRICE_UPDATED,
                "GlobalFuelPrice", saved.getId().toString(),
                "Fuel price updated: " + saved.getFuelType() + " → ₹" + saved.getPricePerUnit(),
                currentUser);

        // Open shifts keep the price snapshotted when they opened, so the admin is warned about them.
        List<?> openShifts = shiftRepository.findActiveShiftsByPump(pumpId);
        int openShiftsCount = openShifts.size();
        String warning = openShiftsCount > 0
                ? openShiftsCount + " shift(s) are currently open at this pump. They will settle at the OLD price. " +
                  "Close all open shifts before the new price takes effect to avoid balance discrepancies."
                : null;

        if (openShiftsCount > 0) {
            // Fire a PRICE_CHANGE_OPEN_SHIFT notification deduped per pump per day.
            // One notification per day is enough — if the admin changes price multiple times
            // on the same day while shifts remain open, we avoid spamming.
            String dedupKey = "PRICE_CHANGE_OPEN_SHIFT:" + businessClock.today() + ":" + pumpId;
            notificationDedupService.maybeInsert(pumpId,
                    NotificationType.PRICE_CHANGE_OPEN_SHIFT, dedupKey,
                    "Fuel Price Changed With Open Shifts",
                    saved.getFuelType() + " price updated to ₹" + saved.getPricePerUnit() +
                    " but " + openShiftsCount + " shift(s) are still open. They will settle at the old price. " +
                    "Close all open shifts to avoid balance discrepancies.");
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SetFuelPriceResponse.builder()
                        .price(saved)
                        .openShiftsCount(openShiftsCount)
                        .openShiftsWarning(warning)
                        .build());
    }
}
