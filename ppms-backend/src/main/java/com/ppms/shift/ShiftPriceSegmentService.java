package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Prices a shift's litres in segments when the fuel price changes while the shift is open: the meter reading of every
 * open nozzle selling that fuel is taken at the change, litres before it keep the old price and litres after it take
 * the new one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftPriceSegmentService {

    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final ShiftReadingPriceSegmentRepository segmentRepository;
    private final ShiftRepository shiftRepository;
    private final NozzleRepository nozzleRepository;
    private final DispensaryUnitRepository duRepository;
    private final BusinessClock businessClock;

    public record NozzleMeterReading(Long nozzleId, BigDecimal meterReading) {
    }

    /** The open nozzles selling the fuel, each with the last meter reading known, to prefill the price-change form. */
    @Transactional(readOnly = true)
    public List<OpenNozzleReadingResponse> openNozzlesSelling(Long pumpId, FuelType fuelType) {
        List<ShiftFuelReading> openings = fuelReadingRepository.findOpenShiftReadingsByPumpIdAndFuelType(pumpId, fuelType);
        Map<Long, Nozzle> nozzleById = nozzlesOf(openings);
        Map<Long, DispensaryUnit> duById = duRepository.findAllById(
                        nozzleById.values().stream().map(Nozzle::getDuId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(DispensaryUnit::getId, Function.identity()));
        Map<Long, List<ShiftReadingPriceSegment>> segmentsByReading = segmentsByReading(openings);
        return openings.stream()
                .map(opening -> {
                    Nozzle nozzle = nozzleById.get(opening.getNozzleId());
                    DispensaryUnit du = duById.get(nozzle.getDuId());
                    return new OpenNozzleReadingResponse(opening.getShiftId(), nozzle.getId(), nozzle.getNozzleNumber(),
                            du != null ? du.getName() : null, opening.getFuelType().name(),
                            lastKnownReading(opening, segmentsByReading.get(opening.getId())), nozzle.getMaxMeterValue());
                })
                .toList();
    }

    /**
     * Ends the running price segment of every open nozzle selling the fuel at the reading entered for it and starts a
     * segment at the new price. Every such nozzle needs exactly one reading; the shift rows are locked first, as a close
     * does, so a shift cannot close half split.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int splitOpenShiftsAtPriceChange(Long pumpId, FuelType fuelType, BigDecimal newPrice,
                                            List<NozzleMeterReading> meterReadings, User currentUser) {
        fuelReadingRepository.findOpenShiftReadingsByPumpIdAndFuelType(pumpId, fuelType).stream()
                .map(ShiftFuelReading::getShiftId).distinct().sorted()
                .forEach(shiftRepository::findByIdForUpdate);
        List<ShiftFuelReading> openings = fuelReadingRepository.findOpenShiftReadingsByPumpIdAndFuelType(pumpId, fuelType);
        Map<Long, BigDecimal> readingByNozzleId = readingsByNozzleId(meterReadings);
        requireOneReadingPerOpenNozzle(fuelType, openings, readingByNozzleId);
        if (openings.isEmpty()) {
            return 0;
        }

        Map<Long, Nozzle> nozzleById = nozzlesOf(openings);
        Map<Long, List<ShiftReadingPriceSegment>> segmentsByReading = segmentsByReading(openings);
        Map<Long, OffsetDateTime> startById = shiftRepository.findAllById(
                        openings.stream().map(ShiftFuelReading::getShiftId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Shift::getId, Shift::getActualStartTime));
        OffsetDateTime now = businessClock.now();
        List<ShiftReadingPriceSegment> toSave = new ArrayList<>();
        for (ShiftFuelReading opening : openings) {
            Nozzle nozzle = nozzleById.get(opening.getNozzleId());
            List<ShiftReadingPriceSegment> segments = segmentsByReading.getOrDefault(opening.getId(), List.of());
            BigDecimal lastReading = lastKnownReading(opening, segments);
            BigDecimal meterReading = readingByNozzleId.get(opening.getNozzleId()).setScale(3, RoundingMode.HALF_UP);
            requireReadingWithinMeter(nozzle, meterReading, lastReading);
            if (segments.isEmpty()) {
                toSave.add(ShiftReadingPriceSegment.builder()
                        .shiftFuelReadingId(opening.getId())
                        .startReading(opening.getStartReading())
                        .pricePerUnit(opening.getPriceSnapshot())
                        .startsAt(startById.get(opening.getShiftId()))
                        .build());
            }
            toSave.add(ShiftReadingPriceSegment.builder()
                    .shiftFuelReadingId(opening.getId())
                    .startReading(meterReading)
                    .pricePerUnit(newPrice)
                    .startsAt(now)
                    .recordedByUserId(currentUser.getId())
                    .build());
        }
        segmentRepository.saveAll(toSave);
        log.info("Price change split open shifts: pump={}, fuel={}, newPrice={}, nozzles={}, by={}",
                pumpId, fuelType, newPrice, readingByNozzleId.keySet(), currentUser.getId());
        return openings.size();
    }

    /** Segments of the readings, oldest first, by reading id. */
    public Map<Long, List<ShiftReadingPriceSegment>> segmentsByReading(Collection<ShiftFuelReading> readings) {
        if (readings.isEmpty()) {
            return Map.of();
        }
        return segmentRepository.findByShiftFuelReadingIdInOrderByStartsAtAscIdAsc(
                        readings.stream().map(ShiftFuelReading::getId).toList()).stream()
                .collect(Collectors.groupingBy(ShiftReadingPriceSegment::getShiftFuelReadingId));
    }

    /**
     * Fills each segment's units up to the next segment's start, the last one up to the closing reading, and returns
     * what the litres are worth. Test litres are known only at close, so they come off the latest segments first. A
     * closing reading below the last price-change reading is refused unless the meter rolled over from the top of its
     * range.
     */
    public BigDecimal priceBySegments(ShiftFuelReading reading, List<ShiftReadingPriceSegment> segments,
                                      BigDecimal endReading, BigDecimal maxMeter, BigDecimal testLitres) {
        ShiftReadingPriceSegment last = segments.get(segments.size() - 1);
        if (endReading.compareTo(last.getStartReading()) < 0
                && !MeterRollover.canRollOverFrom(last.getStartReading(), maxMeter)) {
            throw new BusinessException(ErrorCodes.CLOSING_READING_BELOW_PRICE_CHANGE,
                    Map.of("closingReading", endReading.toPlainString(),
                            "priceChangeReading", last.getStartReading().toPlainString(),
                            "fuelTypeCode", reading.getFuelType().name()),
                    "Closing reading " + endReading.toPlainString() + " is below the reading " +
                    last.getStartReading().toPlainString() + " taken for the " + reading.getFuelType() +
                    " nozzle when the price changed. Please verify the meter reading.");
        }
        for (int i = 0; i < segments.size(); i++) {
            ShiftReadingPriceSegment segment = segments.get(i);
            BigDecimal segmentEnd = i + 1 < segments.size() ? segments.get(i + 1).getStartReading() : endReading;
            segment.setUnitsSold(MeterRollover.unitsBetween(segment.getStartReading(), segmentEnd, maxMeter));
        }
        BigDecimal testLeft = testLitres;
        for (int i = segments.size() - 1; i >= 0 && testLeft.signum() > 0; i--) {
            ShiftReadingPriceSegment segment = segments.get(i);
            BigDecimal taken = segment.getUnitsSold().min(testLeft);
            segment.setUnitsSold(segment.getUnitsSold().subtract(taken));
            testLeft = testLeft.subtract(taken);
        }
        BigDecimal amount = BigDecimal.ZERO;
        for (ShiftReadingPriceSegment segment : segments) {
            amount = amount.add(segment.getUnitsSold().multiply(segment.getPricePerUnit()));
        }
        segmentRepository.saveAll(segments);
        return amount;
    }

    /** The price each fuel of the shift sells at now: the latest segment's price, else the opening price. */
    public Map<String, BigDecimal> currentPricesOf(List<ShiftFuelReading> readings) {
        Map<Long, List<ShiftReadingPriceSegment>> segmentsByReading = segmentsByReading(readings);
        Map<String, BigDecimal> priceByFuelType = new HashMap<>();
        for (ShiftFuelReading reading : readings) {
            List<ShiftReadingPriceSegment> segments = segmentsByReading.get(reading.getId());
            BigDecimal price = segments == null ? reading.getPriceSnapshot()
                    : segments.get(segments.size() - 1).getPricePerUnit();
            priceByFuelType.merge(reading.getFuelType().name(), price, BigDecimal::max);
        }
        return priceByFuelType;
    }

    private static BigDecimal lastKnownReading(ShiftFuelReading opening, List<ShiftReadingPriceSegment> segments) {
        return segments == null || segments.isEmpty()
                ? opening.getStartReading()
                : segments.get(segments.size() - 1).getStartReading();
    }

    private static void requireOneReadingPerOpenNozzle(FuelType fuelType, List<ShiftFuelReading> openings,
                                                       Map<Long, BigDecimal> readingByNozzleId) {
        List<Long> openNozzleIds = openings.stream().map(ShiftFuelReading::getNozzleId).toList();
        if (!openNozzleIds.containsAll(readingByNozzleId.keySet())) {
            throw new BusinessException(ErrorCodes.PRICE_CHANGE_READING_FOR_NOZZLE_WITHOUT_OPEN_SHIFT,
                    Map.of("fuelTypeCode", fuelType.name()),
                    "A meter reading was entered for a nozzle that is not on an open shift selling " + fuelType +
                    ". The shift may have just closed — reload and enter the readings again.");
        }
        List<Long> missing = openNozzleIds.stream().filter(id -> !readingByNozzleId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCodes.PRICE_CHANGE_METER_READINGS_MISSING,
                    Map.of("fuelTypeCode", fuelType.name(), "openNozzleCount", missing.size()),
                    missing.size() + " open " + fuelType + " nozzle(s) need their current meter reading, so the "
                            + "litres sold before the change keep the old price.");
        }
    }

    private static void requireReadingWithinMeter(Nozzle nozzle, BigDecimal meterReading, BigDecimal lastReading) {
        if (meterReading.compareTo(nozzle.getMaxMeterValue()) > 0) {
            throw new BusinessException(ErrorCodes.METER_READING_ABOVE_MAXIMUM,
                    Map.of("meterReading", meterReading.toPlainString(),
                            "nozzleNumber", nozzle.getNozzleNumber(),
                            "maxMeterValue", nozzle.getMaxMeterValue().toPlainString()),
                    "Meter reading " + meterReading.toPlainString() + " for nozzle #" + nozzle.getNozzleNumber()
                            + " exceeds the maximum meter value of " + nozzle.getMaxMeterValue().toPlainString() + ".");
        }
        if (meterReading.compareTo(lastReading) < 0 && !MeterRollover.canRollOverFrom(lastReading, nozzle.getMaxMeterValue())) {
            throw new BusinessException(ErrorCodes.PRICE_CHANGE_READING_BELOW_LAST,
                    Map.of("meterReading", meterReading.toPlainString(),
                            "nozzleNumber", nozzle.getNozzleNumber(),
                            "lastReading", lastReading.toPlainString()),
                    "Meter reading " + meterReading.toPlainString() + " for nozzle #" + nozzle.getNozzleNumber()
                            + " is below its last known reading " + lastReading.toPlainString() + ".");
        }
    }

    private static Map<Long, BigDecimal> readingsByNozzleId(List<NozzleMeterReading> meterReadings) {
        Map<Long, BigDecimal> readingByNozzleId = new HashMap<>();
        if (meterReadings == null) {
            return readingByNozzleId;
        }
        for (NozzleMeterReading reading : meterReadings) {
            if (readingByNozzleId.put(reading.nozzleId(), reading.meterReading()) != null) {
                throw new BusinessException(ErrorCodes.NOZZLE_REPEATED_IN_REQUEST, Map.of(),
                        "Each nozzle can only appear once in the request.");
            }
        }
        return readingByNozzleId;
    }

    private Map<Long, Nozzle> nozzlesOf(List<ShiftFuelReading> openings) {
        return nozzleRepository.findAllById(openings.stream().map(ShiftFuelReading::getNozzleId).toList()).stream()
                .collect(Collectors.toMap(Nozzle::getId, Function.identity()));
    }
}
