package com.ppms.inventory;

import com.ppms.adjustment.FuelDipEntryRepository;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.common.time.DateRangeBounds;
import com.ppms.inventory.WetStockReconciliationCalculator.Dip;
import com.ppms.inventory.WetStockReconciliationCalculator.TankFigures;
import com.ppms.inventory.WetStockReconciliationCalculator.TimedLitres;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.shift.ShiftFuelReading;
import com.ppms.shift.ShiftFuelReadingRepository;
import com.ppms.shift.ShiftStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads a pump's dips, deliveries, meter readings and drains for a date range in a fixed number of queries and
 * reconciles each tank dip to dip. Sales between two dips come from the meters: for every shift nozzle drawing from
 * the tank, what it had dispensed by the closing dip less what it had dispensed by the opening dip, using the meter
 * snapshot taken with a dip while the shift was open, else the opening or closing reading last known by then.
 * A delivery counts with the litres its receipt check measured in the tank, the same litres added to tank stock; the
 * litres sold while it was unloaded are in both that figure and the meters, so they cancel out.
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
    private final DipCheckNozzleReadingRepository dipSnapshotRepository;
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
        Map<Long, TreeMap<LocalDate, Dip>> closingDips = closingDipsByTank(pumpId, windowStart, windowEnd);
        Map<Long, List<TimedLitres>> delivered = deliveredByTank(pumpId, windowStart, windowEnd);
        Map<Long, Map<LocalDate, BigDecimal>> removed = removedByTank(pumpId, loadFrom, to);
        MeterFigures meters = meterFiguresByTank(pumpId, windowStart, windowEnd);

        List<WetStockReconciliationResponse.TankReconciliation> reconciled = tanks.stream()
                .map(tank -> WetStockReconciliationCalculator.reconcile(tank, new TankFigures(
                        closingDips.getOrDefault(tank.getId(), new TreeMap<>()),
                        delivered.getOrDefault(tank.getId(), List.of()),
                        removed.getOrDefault(tank.getId(), Map.of()),
                        meters.salesByTank().getOrDefault(tank.getId(), TankMeterSales.none()),
                        meters.shortfallsByTank().getOrDefault(tank.getId(), List.of())), from, to))
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
    private Map<Long, TreeMap<LocalDate, Dip>> closingDipsByTank(Long pumpId, OffsetDateTime from, OffsetDateTime to) {
        Map<Long, TreeMap<LocalDate, Dip>> byTank = new HashMap<>();
        dipCheckRepository.findByPumpIdAndCheckedAtGreaterThanEqualAndCheckedAtLessThan(pumpId, from, to).stream()
                .sorted(Comparator.comparing(DipCheck::getCheckedAt).thenComparing(DipCheck::getId))
                .forEach(dip -> byTank.computeIfAbsent(dip.getTankId(), id -> new TreeMap<>())
                        .put(businessDayOf(dip.getCheckedAt()), new Dip(dip.getCheckedAt(), dip.getMeasuredQuantity())));
        return byTank;
    }

    private Map<Long, List<TimedLitres>> deliveredByTank(Long pumpId, OffsetDateTime from, OffsetDateTime to) {
        Map<Long, List<TimedLitres>> byTank = new HashMap<>();
        for (TankerDelivery delivery : deliveryRepository
                .findByPumpIdAndDeliveryDateGreaterThanEqualAndDeliveryDateLessThan(pumpId, from, to)) {
            byTank.computeIfAbsent(delivery.getTankId(), id -> new ArrayList<>())
                    .add(new TimedLitres(unloadedAt(delivery), delivery.litresAddedToTank()));
        }
        return byTank;
    }

    /**
     * A delivery carries only its business day. Entered on that day, its entry time places it among the day's dips;
     * entered later, it is placed at the day's start.
     */
    private OffsetDateTime unloadedAt(TankerDelivery delivery) {
        OffsetDateTime enteredAt = delivery.getCreatedAt();
        if (enteredAt != null && businessDayOf(enteredAt).equals(businessDayOf(delivery.getDeliveryDate()))) {
            return enteredAt;
        }
        return delivery.getDeliveryDate();
    }

    /** Fuel drained from a tank for maintenance; drains recorded before they were linked to a tank are left out. */
    private Map<Long, Map<LocalDate, BigDecimal>> removedByTank(Long pumpId, LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, BigDecimal>> byTank = new HashMap<>();
        fuelDipEntryRepository.findByPumpIdAndDipDateBetween(pumpId, from, to).stream()
                .filter(entry -> entry.getTankId() != null)
                .forEach(entry -> byTank.computeIfAbsent(entry.getTankId(), id -> new HashMap<>())
                        .merge(entry.getDipDate(), entry.getLitresRemoved(), BigDecimal::add));
        return byTank;
    }

    private record MeterFigures(Map<Long, TankMeterSales> salesByTank, Map<Long, List<TimedLitres>> shortfallsByTank) {
    }

    /**
     * One meter timeline per shift nozzle: nothing dispensed at the opening, the dip snapshots, and the units sold at
     * the close. A shift with no closing readings yet (open or auto-closed) has no close point.
     */
    private MeterFigures meterFiguresByTank(Long pumpId, OffsetDateTime from, OffsetDateTime to) {
        Map<ShiftNozzle, List<Object[]>> snapshotsByShiftNozzle = new HashMap<>();
        for (Object[] row : dipSnapshotRepository.findSnapshotsTakenWithDipsBetween(pumpId, from, to)) {
            snapshotsByShiftNozzle.computeIfAbsent(new ShiftNozzle((Long) row[0], (Long) row[1]), key -> new ArrayList<>())
                    .add(row);
        }

        Map<Long, List<TankMeterSales.MeterTimeline>> timelinesByTank = new HashMap<>();
        Map<Long, List<TimedLitres>> shortfallsByTank = new HashMap<>();
        for (Object[] row : fuelReadingRepository.findTankReadingsOfShiftsDispensingBetween(pumpId, from, to)) {
            ShiftFuelReading reading = (ShiftFuelReading) row[0];
            ShiftStatus status = (ShiftStatus) row[1];
            OffsetDateTime closedAt = row[3] != null ? (OffsetDateTime) row[3] : (OffsetDateTime) row[4];
            TankMeterSales.MeterTimeline timeline = new TankMeterSales.MeterTimeline((OffsetDateTime) row[2]);
            snapshotsByShiftNozzle.getOrDefault(new ShiftNozzle(reading.getShiftId(), reading.getNozzleId()), List.of())
                    .forEach(snapshot -> timeline.knownAt((OffsetDateTime) snapshot[2], (BigDecimal) snapshot[3]));
            boolean closedWithReadings = !status.isOpen() && reading.getUnitsSold() != null && closedAt != null;
            if (closedWithReadings) {
                timeline.knownAt(closedAt, reading.getUnitsSold());
                if (reading.getStockShortfallLitres().signum() > 0) {
                    shortfallsByTank.computeIfAbsent(reading.getTankId(), id -> new ArrayList<>())
                            .add(new TimedLitres(closedAt, reading.getStockShortfallLitres()));
                }
            }
            timelinesByTank.computeIfAbsent(reading.getTankId(), id -> new ArrayList<>()).add(timeline);
        }

        Map<Long, TankMeterSales> salesByTank = new HashMap<>();
        timelinesByTank.forEach((tankId, timelines) -> salesByTank.put(tankId, new TankMeterSales(timelines)));
        return new MeterFigures(salesByTank, shortfallsByTank);
    }

    private record ShiftNozzle(Long shiftId, Long nozzleId) {
    }

    private LocalDate businessDayOf(OffsetDateTime instant) {
        return instant.atZoneSameInstant(businessClock.zone()).toLocalDate();
    }
}
