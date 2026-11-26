package com.ppms.inventory;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.UndergroundTank;
import com.ppms.shift.MeterRollover;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Measures what a delivery really put into its tank from the dips before and after unloading and the litres the
 * tank's nozzles sold in between, and compares it with the invoice using the pump's tolerances.
 */
@Service
@RequiredArgsConstructor
public class DeliveryReceiptService {

    private final NozzleRepository nozzleRepository;
    private final DispensaryUnitRepository duRepository;
    private final DeliveryNozzleReadingRepository readingRepository;

    /** The measured receipt of one delivery line, with its nozzle readings not yet saved. */
    public record MeasuredReceipt(BigDecimal dipBeforeLitres, BigDecimal dipAfterLitres, BigDecimal soldBetweenLitres,
                                  BigDecimal receivedLitres, BigDecimal observedDensity, BigDecimal invoiceDensity,
                                  List<DeliveryNozzleReading> readings) {
    }

    public List<ReceiptNozzleResponse> findReceiptNozzles(Long pumpId) {
        List<Nozzle> nozzles = nozzleRepository.findActiveTankNozzlesByPumpId(pumpId);
        Map<Long, DispensaryUnit> duById = dispensaryUnitsOf(nozzles);
        return nozzles.stream()
                .map(nozzle -> new ReceiptNozzleResponse(nozzle.getId(), nozzle.getNozzleNumber(),
                        duNameOf(nozzle, duById), nozzle.getTankId(), nozzle.getFuelType().name(),
                        nozzle.getLastReading(), nozzle.getMaxMeterValue()))
                .toList();
    }

    /**
     * Every active nozzle on the tank needs exactly one pair of readings, and no other nozzle may have one;
     * a pair follows the same rollover rules as a shift close.
     */
    public MeasuredReceipt measure(UndergroundTank tank, ReceiptCheckRequest request) {
        Map<Long, Nozzle> tankNozzleById = nozzleRepository.findActiveTankNozzlesByPumpId(tank.getPumpId()).stream()
                .filter(nozzle -> tank.getId().equals(nozzle.getTankId()))
                .collect(Collectors.toMap(Nozzle::getId, Function.identity()));
        Map<Long, ReceiptCheckRequest.NozzleReading> readingByNozzleId = readingsByNozzleId(request);

        if (!tankNozzleById.keySet().containsAll(readingByNozzleId.keySet())) {
            throw new BusinessException(ErrorCodes.RECEIPT_READING_FOR_UNMAPPED_NOZZLE,
                    Map.of("tankIdentifier", tank.getTankIdentifier()),
                    "A meter reading was entered for a nozzle that does not draw from tank '" + tank.getTankIdentifier()
                            + "'. Reload the form and enter the readings again.");
        }
        List<Nozzle> missing = tankNozzleById.values().stream()
                .filter(nozzle -> !readingByNozzleId.containsKey(nozzle.getId()))
                .toList();
        if (!missing.isEmpty()) {
            String nozzleNumbers = missing.stream().map(nozzle -> "#" + nozzle.getNozzleNumber())
                    .collect(Collectors.joining(", "));
            throw new BusinessException(ErrorCodes.RECEIPT_NOZZLE_READINGS_MISSING,
                    Map.of("tankIdentifier", tank.getTankIdentifier(), "nozzleNumbers", nozzleNumbers),
                    "Enter the meter readings before and after unloading for nozzle " + nozzleNumbers
                            + " on tank '" + tank.getTankIdentifier() + "', so fuel sold meanwhile is not counted as short.");
        }

        List<DeliveryNozzleReading> readings = new ArrayList<>();
        BigDecimal soldBetween = BigDecimal.ZERO;
        for (Nozzle nozzle : tankNozzleById.values()) {
            ReceiptCheckRequest.NozzleReading reading = readingByNozzleId.get(nozzle.getId());
            BigDecimal before = reading.readingBefore().setScale(3, RoundingMode.HALF_UP);
            BigDecimal after = reading.readingAfter().setScale(3, RoundingMode.HALF_UP);
            assertWithinMeter(nozzle, before);
            assertWithinMeter(nozzle, after);
            if (after.compareTo(before) < 0 && !MeterRollover.canRollOverFrom(before, nozzle.getMaxMeterValue())) {
                throw new BusinessException(ErrorCodes.RECEIPT_AFTER_READING_BELOW_BEFORE,
                        Map.of("nozzleNumber", nozzle.getNozzleNumber(),
                               "readingBefore", before.toPlainString(),
                               "readingAfter", after.toPlainString()),
                        "Meter reading after unloading " + after.toPlainString() + " for nozzle #" + nozzle.getNozzleNumber()
                                + " is below the reading before unloading " + before.toPlainString() + ".");
            }
            BigDecimal litresSold = MeterRollover.unitsBetween(before, after, nozzle.getMaxMeterValue());
            soldBetween = soldBetween.add(litresSold);
            readings.add(DeliveryNozzleReading.builder()
                    .nozzleId(nozzle.getId())
                    .readingBefore(before)
                    .readingAfter(after)
                    .litresSold(litresSold)
                    .build());
        }

        BigDecimal dipBefore = request.dipBeforeLitres().setScale(3, RoundingMode.HALF_UP);
        BigDecimal dipAfter = request.dipAfterLitres().setScale(3, RoundingMode.HALF_UP);
        BigDecimal received = ReceiptCheckCalculator.receivedLitres(dipBefore, dipAfter, soldBetween);
        if (received.signum() <= 0) {
            throw new BusinessException(ErrorCodes.RECEIVED_LITRES_NOT_POSITIVE,
                    Map.of("tankIdentifier", tank.getTankIdentifier(), "receivedLitres", received.toPlainString()),
                    "The dips and meter readings for tank '" + tank.getTankIdentifier() + "' give " + received.toPlainString()
                            + " L received. Check the dip after unloading is higher than the dip before.");
        }
        return new MeasuredReceipt(dipBefore, dipAfter, soldBetween.setScale(3, RoundingMode.HALF_UP), received,
                densityOf(request.observedDensity()), densityOf(request.invoiceDensity()), readings);
    }

    /** Copies the measured receipt onto the delivery, with the pump's tolerances at this moment. */
    public void applyTo(TankerDelivery delivery, MeasuredReceipt receipt, PumpLocation pump) {
        delivery.setDipBeforeLitres(receipt.dipBeforeLitres());
        delivery.setDipAfterLitres(receipt.dipAfterLitres());
        delivery.setSoldBetweenLitres(receipt.soldBetweenLitres());
        delivery.setReceivedLitres(receipt.receivedLitres());
        delivery.setObservedDensity(receipt.observedDensity());
        delivery.setInvoiceDensity(receipt.invoiceDensity());
        delivery.setReceiptTolerancePercent(pump.getReceiptTolerancePercent());
        delivery.setDensityTolerance(pump.getDensityTolerance());
        delivery.setDensityFlagged(ReceiptCheckCalculator.isDensityFlagged(
                ReceiptCheckCalculator.densityDifference(receipt.observedDensity(), receipt.invoiceDensity()),
                pump.getDensityTolerance()));
        compareWithInvoice(delivery);
    }

    /** Re-compares a checked delivery with its invoice litres, using the tolerance it was checked with. */
    public void compareWithInvoice(TankerDelivery delivery) {
        delivery.setVarianceLitres(delivery.getReceivedLitres().subtract(delivery.getQuantityDelivered())
                .setScale(3, RoundingMode.HALF_UP));
        delivery.setCheckStatus(ReceiptCheckCalculator.statusOf(delivery.getQuantityDelivered(),
                delivery.getReceivedLitres(), delivery.getReceiptTolerancePercent()));
    }

    public void saveReadings(TankerDelivery delivery, MeasuredReceipt receipt) {
        receipt.readings().forEach(reading -> reading.setTankerDeliveryId(delivery.getId()));
        readingRepository.saveAll(receipt.readings());
    }

    private static Map<Long, ReceiptCheckRequest.NozzleReading> readingsByNozzleId(ReceiptCheckRequest request) {
        Map<Long, ReceiptCheckRequest.NozzleReading> readingByNozzleId = new HashMap<>();
        for (ReceiptCheckRequest.NozzleReading reading : request.nozzleReadingsOrEmpty()) {
            if (readingByNozzleId.put(reading.nozzleId(), reading) != null) {
                throw new BusinessException(ErrorCodes.NOZZLE_REPEATED_IN_REQUEST, Map.of(),
                        "Each nozzle can only appear once in the request.");
            }
        }
        return readingByNozzleId;
    }

    private static void assertWithinMeter(Nozzle nozzle, BigDecimal reading) {
        if (reading.compareTo(nozzle.getMaxMeterValue()) > 0) {
            throw new BusinessException(ErrorCodes.METER_READING_ABOVE_MAXIMUM,
                    Map.of("meterReading", reading.toPlainString(),
                           "nozzleNumber", nozzle.getNozzleNumber(),
                           "maxMeterValue", nozzle.getMaxMeterValue().toPlainString()),
                    "Meter reading " + reading.toPlainString() + " for nozzle #" + nozzle.getNozzleNumber()
                            + " exceeds the maximum meter value of " + nozzle.getMaxMeterValue().toPlainString()
                            + ". Please verify the meter reading.");
        }
    }

    private static BigDecimal densityOf(BigDecimal density) {
        return density != null ? density.setScale(1, RoundingMode.HALF_UP) : null;
    }

    private Map<Long, DispensaryUnit> dispensaryUnitsOf(List<Nozzle> nozzles) {
        return duRepository.findAllById(nozzles.stream().map(Nozzle::getDuId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(DispensaryUnit::getId, Function.identity()));
    }

    static String duNameOf(Nozzle nozzle, Map<Long, DispensaryUnit> duById) {
        DispensaryUnit du = duById.get(nozzle.getDuId());
        return du != null ? du.getName() : null;
    }
}
