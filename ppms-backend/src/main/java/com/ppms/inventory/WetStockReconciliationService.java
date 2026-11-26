package com.ppms.inventory;

import com.ppms.adjustment.FuelDipEntryRepository;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.common.time.DateRangeBounds;
import com.ppms.inventory.WetStockReconciliationCalculator.TankDailyFigures;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.shift.ShiftFuelReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads a pump's dips, deliveries, metered sales and drains for a date range in a fixed number of queries and
 * reconciles each tank. Sales are the closed shifts' meter litres per tank, the same litres the DAY balance sheet sums.
 * A delivery counts with the litres its receipt check measured in the tank, the same litres added to tank stock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WetStockReconciliationService {

    static final int MAX_RANGE_DAYS = 92;
    /** How far before the range an opening dip is looked for; older dips leave the first period without an opening. */
    static final int OPENING_DIP_LOOKBACK_DAYS = 31;

    private final UndergroundTankRepository tankRepository;
    private final DipCheckRepository dipCheckRepository;
    private final TankerDeliveryRepository deliveryRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final FuelDipEntryRepository fuelDipEntryRepository;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public WetStockReconciliationResponse reconcile(Long pumpId, LocalDate from, LocalDate to, Long tankId) {
        requireValidRange(from, to);
        List<UndergroundTank> tanks = tankRepository.findByPumpId(pumpId).stream()
                .filter(tank -> tank.getStatus() != TankStatus.DECOMMISSIONED)
                .filter(tank -> tankId == null || tank.getId().equals(tankId))
                .sorted(Comparator.comparing(UndergroundTank::getTankIdentifier))
                .toList();
        if (tankId != null && tanks.isEmpty()) {
            throw new ResourceNotFoundException(EntityType.TANK, "Tank not found");
        }

        LocalDate loadFrom = from.minusDays(OPENING_DIP_LOOKBACK_DAYS);
        OffsetDateTime windowStart = businessClock.startOfDay(loadFrom);
        OffsetDateTime windowEnd = businessClock.startOfDay(to.plusDays(1));
        Map<Long, TreeMap<LocalDate, BigDecimal>> closingDips = closingDipsByTank(pumpId, windowStart, windowEnd);
        Map<Long, Map<LocalDate, BigDecimal>> delivered = deliveredByTank(pumpId, windowStart, windowEnd);
        Map<Long, Map<LocalDate, BigDecimal>> sold = soldByTank(pumpId, loadFrom, to);
        Map<Long, Map<LocalDate, BigDecimal>> removed = removedByTank(pumpId, loadFrom, to);

        List<WetStockReconciliationResponse.TankReconciliation> reconciled = tanks.stream()
                .map(tank -> WetStockReconciliationCalculator.reconcile(tank, new TankDailyFigures(
                        closingDips.getOrDefault(tank.getId(), new TreeMap<>()),
                        delivered.getOrDefault(tank.getId(), Map.of()),
                        sold.getOrDefault(tank.getId(), Map.of()),
                        removed.getOrDefault(tank.getId(), Map.of())), from, to))
                .toList();
        log.debug("Wet-stock reconciliation pump={} from={} to={} tanks={}", pumpId, from, to, reconciled.size());
        return new WetStockReconciliationResponse(from, to, reconciled);
    }

    private void requireValidRange(LocalDate from, LocalDate to) {
        DateRangeBounds.requireStartNotAfterEnd(from, to);
        if (to.isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.DATE_RANGE_END_IN_FUTURE, Map.of(),
                    "'to' date cannot be in the future");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCodes.DATE_RANGE_TOO_LONG, Map.of("maxDays", MAX_RANGE_DAYS),
                    "Choose a date range of at most " + MAX_RANGE_DAYS + " days");
        }
    }

    /** The last dip of each business day per tank; later dips on the same day replace earlier ones. */
    private Map<Long, TreeMap<LocalDate, BigDecimal>> closingDipsByTank(Long pumpId, OffsetDateTime from, OffsetDateTime to) {
        Map<Long, TreeMap<LocalDate, BigDecimal>> byTank = new HashMap<>();
        dipCheckRepository.findByPumpIdAndCheckedAtGreaterThanEqualAndCheckedAtLessThan(pumpId, from, to).stream()
                .sorted(Comparator.comparing(DipCheck::getCheckedAt).thenComparing(DipCheck::getId))
                .forEach(dip -> byTank.computeIfAbsent(dip.getTankId(), id -> new TreeMap<>())
                        .put(businessDayOf(dip.getCheckedAt()), dip.getMeasuredQuantity()));
        return byTank;
    }

    private Map<Long, Map<LocalDate, BigDecimal>> deliveredByTank(Long pumpId, OffsetDateTime from, OffsetDateTime to) {
        Map<Long, Map<LocalDate, BigDecimal>> byTank = new HashMap<>();
        for (TankerDelivery delivery : deliveryRepository
                .findByPumpIdAndDeliveryDateGreaterThanEqualAndDeliveryDateLessThan(pumpId, from, to)) {
            addTo(byTank, delivery.getTankId(), businessDayOf(delivery.getDeliveryDate()), delivery.litresAddedToTank());
        }
        return byTank;
    }

    private Map<Long, Map<LocalDate, BigDecimal>> soldByTank(Long pumpId, LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, BigDecimal>> byTank = new HashMap<>();
        for (Object[] row : fuelReadingRepository.sumClosedLitresSoldByTankAndShiftDate(pumpId, from, to)) {
            addTo(byTank, (Long) row[0], (LocalDate) row[1], (BigDecimal) row[2]);
        }
        return byTank;
    }

    /** Fuel drained from a tank for maintenance; drains recorded before they were linked to a tank are left out. */
    private Map<Long, Map<LocalDate, BigDecimal>> removedByTank(Long pumpId, LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, BigDecimal>> byTank = new HashMap<>();
        fuelDipEntryRepository.findByPumpIdAndDipDateBetween(pumpId, from, to).stream()
                .filter(entry -> entry.getTankId() != null)
                .forEach(entry -> addTo(byTank, entry.getTankId(), entry.getDipDate(), entry.getLitresRemoved()));
        return byTank;
    }

    private static void addTo(Map<Long, Map<LocalDate, BigDecimal>> byTank, Long tankId, LocalDate day, BigDecimal litres) {
        byTank.computeIfAbsent(tankId, id -> new HashMap<>()).merge(day, litres, BigDecimal::add);
    }

    private LocalDate businessDayOf(OffsetDateTime instant) {
        return instant.atZoneSameInstant(businessClock.zone()).toLocalDate();
    }
}
