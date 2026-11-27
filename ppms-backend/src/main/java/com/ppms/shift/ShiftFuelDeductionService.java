package com.ppms.shift;

import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.InventoryLot;
import com.ppms.inventory.InventoryLotRepository;
import com.ppms.inventory.LotConsumption;
import com.ppms.inventory.LotConsumptionRepository;
import com.ppms.inventory.LotConsumptionSource;
import com.ppms.inventory.LotStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FIFO fuel deduction for live shift closes and backfilled shifts.
 * Lock order: the caller's shift row, then {@link #lockTanksForDeduction}, then the lots.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class ShiftFuelDeductionService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final InventoryLotRepository inventoryLotRepository;
    private final LotConsumptionRepository lotConsumptionRepository;
    private final UndergroundTankRepository tankRepository;
    private final BusinessClock businessClock;
    private final ShiftFuelReadingRepository fuelReadingRepository;

    /**
     * Locks, in one id-ordered query, every tank a FIFO deduction can touch, so concurrent deliveries,
     * dip checks and other shift closes cannot overwrite currentStock. Readings without a frozen tank
     * deduct across all of the pump's tanks of that fuel type.
     */
    public void lockTanksForDeduction(Long pumpId, Collection<Long> frozenTankIds, Collection<FuelType> unscopedFuelTypes) {
        Set<Long> tankIds = new HashSet<>(frozenTankIds);
        if (!unscopedFuelTypes.isEmpty()) {
            tankIds.addAll(tankRepository.findIdsByPumpIdAndFuelTypeIn(pumpId, unscopedFuelTypes));
        }
        if (!tankIds.isEmpty()) {
            tankRepository.findAllByIdForUpdate(tankIds);
        }
    }

    /**
     * Deducts a live shift's sales from the oldest active lots. When tankId is set (frozen at shift open)
     * only that tank's lots are used, matching the physical pipe connection. The meters are the truth, so litres the
     * book stock cannot cover do not block the close: they are returned as the shortfall, for a later delivery to cover.
     */
    public BigDecimal deductForShiftClose(Long shiftId, Long pumpId, FuelType fuelType, Long tankId,
                                          BigDecimal unitsToDeduct, OffsetDateTime shiftStart) {
        List<InventoryLot> lots = tankId != null
                ? inventoryLotRepository.findActiveLotsByTankFifo(tankId)
                : inventoryLotRepository.findActiveLotsByPumpAndFuelTypeFifo(pumpId, fuelType);

        if (lots.isEmpty() && tankId != null) {
            lots = healMissingLots(tankId, fuelType, pumpId, shiftStart);
        }

        BigDecimal totalAvailable = lots.stream()
                .map(InventoryLot::getRemainingQuantity)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal deducted = unitsToDeduct.min(totalAvailable);
        consumeOldestFirst(shiftId, lots, deducted);
        BigDecimal shortfall = unitsToDeduct.subtract(deducted).setScale(3, RoundingMode.HALF_UP);
        if (shortfall.signum() > 0) {
            log.warn("Shift {} sold {}L of {} from tank {} but book stock held only {}L; shortfall {}L awaits a delivery",
                    shiftId, unitsToDeduct, fuelType, tankId, totalAvailable, shortfall);
        }
        return shortfall;
    }

    /**
     * Takes litres shifts sold beyond the tank's book stock from a newly recorded delivery's lot, oldest shift first,
     * so tank stock and the lot ledger agree again. The caller has locked the tank.
     */
    public void coverShortfallsFromDelivery(Long tankId, InventoryLot lot) {
        List<ShiftFuelReading> uncovered = fuelReadingRepository.findUncoveredShortfallsByTankId(tankId);
        List<ShiftFuelReading> covered = new ArrayList<>();
        for (ShiftFuelReading reading : uncovered) {
            if (lot.getRemainingQuantity().signum() <= 0) {
                break;
            }
            BigDecimal take = reading.getStockShortfallLitres().min(lot.getRemainingQuantity());
            consumeOldestFirst(reading.getShiftId(), List.of(lot), take);
            reading.setStockShortfallLitres(reading.getStockShortfallLitres().subtract(take).setScale(3, RoundingMode.HALF_UP));
            covered.add(reading);
            log.info("Delivery lot {} covered {}L of shift {}'s stock shortfall on tank {}", lot.getId(), take,
                    reading.getShiftId(), tankId);
        }
        fuelReadingRepository.saveAll(covered);
    }

    /**
     * Puts a closed shift's FIFO draw back into the lots and tanks it came from and forgets it, so an amended shift is
     * deducted again from scratch. The caller has locked the tanks with {@link #lockTanksForDeduction}.
     */
    public void restoreShiftCloseConsumption(Long shiftId) {
        List<LotConsumption> consumptions = lotConsumptionRepository.findByShiftId(shiftId).stream()
                .filter(consumption -> consumption.getSourceType() == LotConsumptionSource.SHIFT_CLOSE)
                .toList();
        if (consumptions.isEmpty()) {
            return;
        }
        Map<Long, InventoryLot> lotsById = inventoryLotRepository.findAllById(
                        consumptions.stream().map(LotConsumption::getLotId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));
        Map<Long, UndergroundTank> tanksById = tankRepository.findAllById(lotsById.values().stream()
                        .map(InventoryLot::getTankId).filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(UndergroundTank::getId, Function.identity()));
        for (LotConsumption consumption : consumptions) {
            InventoryLot lot = lotsById.get(consumption.getLotId());
            lot.setRemainingQuantity(lot.getRemainingQuantity().add(consumption.getQuantityConsumed()).setScale(3, RoundingMode.HALF_UP));
            lot.setStatus(LotStatus.ACTIVE);
            UndergroundTank tank = tanksById.get(lot.getTankId());
            if (tank != null) {
                tank.setCurrentStock(tank.getCurrentStock().add(consumption.getQuantityConsumed()).setScale(3, RoundingMode.HALF_UP));
            }
        }
        inventoryLotRepository.saveAll(lotsById.values());
        tankRepository.saveAll(tanksById.values());
        lotConsumptionRepository.deleteAll(consumptions);
        lotConsumptionRepository.flush();
        log.info("Restored FIFO draw of shift {}: {} lot consumptions put back", shiftId, consumptions.size());
    }

    /**
     * Deducts a backfilled shift's sales using only lots delivered before {@code asOf}, so stock that arrived
     * after the historical shift is never drawn down. The caller has already checked availability.
     */
    public void deductForBackfill(Long shiftId, Long pumpId, FuelType fuelType, Long tankId,
                                  BigDecimal unitsToDeduct, OffsetDateTime asOf) {
        consumeOldestFirst(shiftId, findLotsAvailableAsOf(pumpId, fuelType, tankId, asOf), unitsToDeduct);
    }

    public BigDecimal availableStockAsOf(Long pumpId, FuelType fuelType, Long tankId, OffsetDateTime asOf) {
        return findLotsAvailableAsOf(pumpId, fuelType, tankId, asOf).stream()
                .map(InventoryLot::getRemainingQuantity)
                .reduce(ZERO, BigDecimal::add);
    }

    private List<InventoryLot> findLotsAvailableAsOf(Long pumpId, FuelType fuelType, Long tankId, OffsetDateTime asOf) {
        return tankId != null
                ? inventoryLotRepository.findActiveLotsByTankAvailableAsOf(tankId, asOf)
                : inventoryLotRepository.findActiveLotsByPumpAndFuelTypeAvailableAsOf(pumpId, fuelType, asOf);
    }

    private void consumeOldestFirst(Long shiftId, List<InventoryLot> lots, BigDecimal unitsToDeduct) {
        Set<Long> tankIds = lots.stream()
                .map(InventoryLot::getTankId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UndergroundTank> tanksById = tankRepository.findAllById(tankIds).stream()
                .collect(Collectors.toMap(UndergroundTank::getId, Function.identity()));

        List<InventoryLot> touchedLots = new ArrayList<>();
        List<LotConsumption> consumptions = new ArrayList<>();
        Map<Long, UndergroundTank> modifiedTanks = new HashMap<>();
        BigDecimal remaining = unitsToDeduct;

        for (InventoryLot lot : lots) {
            if (remaining.compareTo(ZERO) <= 0) break;

            BigDecimal consume = remaining.min(lot.getRemainingQuantity());
            lot.setRemainingQuantity(lot.getRemainingQuantity().subtract(consume).setScale(3, RoundingMode.HALF_UP));
            if (lot.getRemainingQuantity().compareTo(ZERO) == 0) {
                lot.setStatus(LotStatus.EXHAUSTED);
            }
            touchedLots.add(lot);

            consumptions.add(LotConsumption.builder()
                    .lotId(lot.getId())
                    .sourceType(LotConsumptionSource.SHIFT_CLOSE)
                    .shiftId(shiftId)
                    .quantityConsumed(consume)
                    .costPricePerUnit(lot.getCostPricePerUnit())
                    .build());

            UndergroundTank tank = tanksById.get(lot.getTankId());
            if (tank != null) {
                tank.setCurrentStock(tank.getCurrentStock().subtract(consume).setScale(3, RoundingMode.HALF_UP));
                modifiedTanks.put(lot.getTankId(), tank);
            }

            remaining = remaining.subtract(consume);
        }

        inventoryLotRepository.saveAll(touchedLots);
        lotConsumptionRepository.saveAll(consumptions);
        tankRepository.saveAll(modifiedTanks.values());
    }

    /**
     * A tank with stock but no active lots was seeded or dip-adjusted without a delivery record.
     * Creates one synthetic lot for the current stock, costed at the latest real delivery, so the shift can close.
     * The lot is dated at the shift's start: the stock was there before this shift drew on it.
     */
    private List<InventoryLot> healMissingLots(Long tankId, FuelType fuelType, Long pumpId, OffsetDateTime shiftStart) {
        return tankRepository.findById(tankId)
                .filter(tank -> tank.getCurrentStock().compareTo(ZERO) > 0)
                .map(tank -> {
                    BigDecimal stock = tank.getCurrentStock().setScale(3, RoundingMode.HALF_UP);
                    BigDecimal costPrice = inventoryLotRepository
                            .findFirstByPumpIdAndFuelTypeAndIsDipAdjustmentFalseOrderByDeliveryDateDesc(pumpId, fuelType)
                            .map(InventoryLot::getCostPricePerUnit)
                            .orElse(BigDecimal.ZERO);
                    InventoryLot lot = inventoryLotRepository.save(InventoryLot.builder()
                            .tankerDeliveryId(null)
                            .tankId(tankId)
                            .fuelType(fuelType)
                            .pumpId(pumpId)
                            .originalQuantity(stock)
                            .remainingQuantity(stock)
                            .costPricePerUnit(costPrice)
                            .deliveryDate(shiftStart != null ? shiftStart : businessClock.now())
                            .isDipAdjustment(true)
                            .status(LotStatus.ACTIVE)
                            .build());
                    log.warn("Tank {} has {}L stock but no inventory lots — created synthetic lot (id={}, costPrice={}) to unblock shift close. " +
                             "Record a tanker delivery to enable proper COGS tracking.", tankId, stock, lot.getId(), costPrice);
                    return List.of(lot);
                })
                .orElse(List.of());
    }
}
