package com.ppms.pump;

import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.pump.SetupChecklistResponse.Item;
import com.ppms.pump.SetupChecklistResponse.Step;
import com.ppms.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Works out which setup steps a new pump still needs: dispensers and nozzles, tanks with every nozzle linked,
 * today's fuel prices, shift definitions, staff and GST details; credit clients are optional.
 * The state of any number of pumps is read with a fixed number of queries.
 */
@Service
@RequiredArgsConstructor
public class SetupChecklistService {

    private final SetupChecklistRepository checklistRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final GlobalFuelPriceRepository fuelPriceRepository;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public SetupChecklistResponse getChecklist(Long pumpId) {
        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Pump not found: " + pumpId));
        return checklistsFor(List.of(pump)).get(pumpId);
    }

    /** The checklist of each given pump, keyed by pump id. */
    @Transactional(readOnly = true)
    public Map<Long, SetupChecklistResponse> checklistsFor(Collection<PumpLocation> pumps) {
        if (pumps.isEmpty()) {
            return Map.of();
        }
        Set<Long> pumpIds = pumps.stream().map(PumpLocation::getId).collect(Collectors.toSet());
        Map<Long, List<ActiveNozzle>> nozzlesByPump = checklistRepository.findActiveNozzles(pumpIds, NozzleStatus.ACTIVE)
                .stream()
                .map(row -> new ActiveNozzle((Long) row[0], (FuelType) row[1], (Long) row[2]))
                .collect(Collectors.groupingBy(ActiveNozzle::pumpId));
        Map<Long, Long> tanks = countsByPump(checklistRepository.countTanksInUse(pumpIds, TankStatus.DECOMMISSIONED));
        Map<Long, Long> shiftDefinitions =
                countsByPump(checklistRepository.countShiftDefinitionsInEffect(pumpIds, businessClock.today()));
        Map<Long, Long> staff = countsByPump(checklistRepository.countStaff(pumpIds, UserStatus.ACTIVE));
        Map<Long, Long> creditClients = countsByPump(checklistRepository.countActiveCreditClients(pumpIds));
        Map<Long, List<GlobalFuelPrice>> pricesByPump = fuelPriceRepository.findCurrentPricesForPumps(pumpIds).stream()
                .collect(Collectors.groupingBy(GlobalFuelPrice::getPumpId));

        Map<Long, SetupChecklistResponse> checklists = new HashMap<>();
        for (PumpLocation pump : pumps) {
            Long id = pump.getId();
            List<ActiveNozzle> nozzles = nozzlesByPump.getOrDefault(id, List.of());
            checklists.put(id, SetupChecklistResponse.of(id, List.of(
                    dispensersItem(nozzles),
                    tanksItem(nozzles, tanks.getOrDefault(id, 0L)),
                    fuelPricesItem(nozzles, pricesByPump.getOrDefault(id, List.of())),
                    shiftDefinitionsItem(shiftDefinitions.getOrDefault(id, 0L)),
                    staffItem(staff.getOrDefault(id, 0L)),
                    gstItem(pump),
                    creditClientsItem(creditClients.getOrDefault(id, 0L)))));
        }
        return checklists;
    }

    private record ActiveNozzle(Long pumpId, FuelType fuelType, Long tankId) {
    }

    private static Map<Long, Long> countsByPump(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    private static Item dispensersItem(List<ActiveNozzle> nozzles) {
        return nozzles.isEmpty()
                ? new Item(Step.DISPENSERS, false, false, "Add a dispensing unit and its nozzles")
                : new Item(Step.DISPENSERS, true, false, plural(nozzles.size(), "active nozzle"));
    }

    private static Item tanksItem(List<ActiveNozzle> nozzles, long tankCount) {
        if (tankCount == 0) {
            return new Item(Step.TANKS, false, false, "Add an underground tank");
        }
        if (nozzles.isEmpty()) {
            return new Item(Step.TANKS, false, false, "Add nozzles, then link each one to a tank");
        }
        long unlinked = nozzles.stream().filter(nozzle -> nozzle.tankId() == null).count();
        return unlinked == 0
                ? new Item(Step.TANKS, true, false, plural(tankCount, "tank") + ", every nozzle linked")
                : new Item(Step.TANKS, false, false, plural(unlinked, "nozzle") + " not linked to a tank");
    }

    private Item fuelPricesItem(List<ActiveNozzle> nozzles, List<GlobalFuelPrice> currentPrices) {
        Map<FuelType, GlobalFuelPrice> priceByFuel = currentPrices.stream()
                .collect(Collectors.toMap(GlobalFuelPrice::getFuelType, price -> price, (first, second) -> first));
        Set<FuelType> fuelsSold = EnumSet.noneOf(FuelType.class);
        nozzles.forEach(nozzle -> fuelsSold.add(nozzle.fuelType()));
        Set<FuelType> fuelsToPrice = fuelsSold.isEmpty() ? priceByFuel.keySet() : fuelsSold;
        if (fuelsToPrice.isEmpty()) {
            return new Item(Step.FUEL_PRICES, false, false, "Set today's fuel prices");
        }
        OffsetDateTime startOfToday = businessClock.startOfDay(businessClock.today());
        long missingOrOld = fuelsToPrice.stream()
                .filter(fuel -> !priceByFuel.containsKey(fuel)
                        || priceByFuel.get(fuel).getEffectiveFrom().isBefore(startOfToday))
                .count();
        return missingOrOld == 0
                ? new Item(Step.FUEL_PRICES, true, false, "Prices set today")
                : new Item(Step.FUEL_PRICES, false, false, plural(missingOrOld, "fuel") + " without today's price");
    }

    private static Item shiftDefinitionsItem(long count) {
        return count == 0
                ? new Item(Step.SHIFT_DEFINITIONS, false, false, "Define the shifts the pump runs")
                : new Item(Step.SHIFT_DEFINITIONS, true, false, plural(count, "shift") + " defined");
    }

    private static Item staffItem(long count) {
        return count == 0
                ? new Item(Step.STAFF, false, false, "Add your manager and operators")
                : new Item(Step.STAFF, true, false, plural(count, "active staff member"));
    }

    private static Item gstItem(PumpLocation pump) {
        boolean complete = isFilled(pump.getGstin()) && isFilled(pump.getLegalName()) && isFilled(pump.getStateName());
        return complete
                ? new Item(Step.GST_DETAILS, true, false, "GSTIN " + pump.getGstin())
                : new Item(Step.GST_DETAILS, false, false, "Add GSTIN, legal name and state for tax invoices");
    }

    private static Item creditClientsItem(long count) {
        return count == 0
                ? new Item(Step.CREDIT_CLIENTS, false, true, "Add customers who buy on credit, if any")
                : new Item(Step.CREDIT_CLIENTS, true, true, plural(count, "credit client"));
    }

    private static boolean isFilled(String value) {
        return value != null && !value.isBlank();
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
