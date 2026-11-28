package com.ppms.pump;

import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.fuel.GlobalFuelPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupChecklistServiceTest {

    private static final long PUMP_ID = 7L;

    @Mock private SetupChecklistRepository checklistRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private GlobalFuelPriceRepository fuelPriceRepository;
    @Mock private BusinessClock businessClock;

    @InjectMocks private SetupChecklistService service;

    private final PumpLocation pump = PumpLocation.builder().id(PUMP_ID).build();

    @Test
    void nozzleNotLinkedToATankLeavesTheTankStepOpen() {
        givenNozzles(nozzle(FuelType.PETROL, 31L), nozzle(FuelType.PETROL, null));
        when(checklistRepository.countTanksInUse(anyCollection(), eq(TankStatus.DECOMMISSIONED)))
                .thenReturn(rows(new Object[]{PUMP_ID, 1L}));

        SetupChecklistResponse.Item tanks = step(SetupChecklistResponse.Step.TANKS);

        assertThat(tanks.done()).isFalse();
        assertThat(tanks.detail()).isEqualTo("1 nozzle not linked to a tank");
        assertThat(tanks.detailCode()).isEqualTo("SETUP_NOZZLES_NOT_LINKED");
        assertThat(tanks.detailParams()).containsEntry("count", 1L);
    }

    @Test
    void fuelSoldWithoutAPriceLeavesThePriceStepOpenAndAPriceForItClosesIt() {
        givenNozzles(nozzle(FuelType.DIESEL, null));

        SetupChecklistResponse.Item unpriced = step(SetupChecklistResponse.Step.FUEL_PRICES);
        assertThat(unpriced.done()).isFalse();
        assertThat(unpriced.detail()).isEqualTo("1 fuel without a price");

        when(fuelPriceRepository.findCurrentPricesForPumps(anyCollection())).thenReturn(List.of(GlobalFuelPrice.builder()
                .pumpId(PUMP_ID).fuelType(FuelType.DIESEL).pricePerUnit(new BigDecimal("90.00")).build()));
        SetupChecklistResponse.Item priced = step(SetupChecklistResponse.Step.FUEL_PRICES);
        assertThat(priced.done()).isTrue();
        assertThat(priced.detailCode()).isEqualTo("SETUP_FUEL_PRICES_SET");
    }

    @Test
    void newPumpWithNothingSetUpHasNoStepsDone() {
        SetupChecklistResponse checklist = service.checklistsFor(List.of(pump)).get(PUMP_ID);

        assertThat(checklist.doneCount()).isZero();
        assertThat(checklist.items()).noneMatch(SetupChecklistResponse.Item::done);
        assertThat(checklist.items().get(0).detail()).isEqualTo("Add a dispensing unit and its nozzles");
    }

    @Test
    void gstStepNeedsGstinLegalNameAndState() {
        pump.setGstin("27AAAPL1234C1ZV");
        pump.setLegalName("Lakshmi Fuels Pvt Ltd");
        assertThat(step(SetupChecklistResponse.Step.GST_DETAILS).done()).isFalse();

        pump.setStateName("Maharashtra");
        assertThat(step(SetupChecklistResponse.Step.GST_DETAILS).done()).isTrue();
    }

    private void givenNozzles(Object[]... nozzles) {
        when(checklistRepository.findActiveNozzles(anyCollection(), eq(NozzleStatus.ACTIVE))).thenReturn(rows(nozzles));
    }

    private SetupChecklistResponse.Item step(SetupChecklistResponse.Step step) {
        return service.checklistsFor(List.of(pump)).get(PUMP_ID).items().stream()
                .filter(item -> item.step() == step)
                .findFirst()
                .orElseThrow();
    }

    private static Object[] nozzle(FuelType fuelType, Long tankId) {
        return new Object[]{PUMP_ID, fuelType, tankId};
    }

    private static List<Object[]> rows(Object[]... rows) {
        return new ArrayList<>(List.of(rows));
    }
}
