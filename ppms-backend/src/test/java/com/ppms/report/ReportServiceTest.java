package com.ppms.report;

import com.ppms.fuel.FuelType;
import com.ppms.inventory.*;
import com.ppms.shift.*;
import com.ppms.expense.PumpExpenseRepository;
import com.ppms.credit.*;
import com.ppms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock ShiftRepository shiftRepository;
    @Mock ShiftFuelReadingRepository fuelReadingRepository;
    @Mock LotConsumptionRepository lotConsumptionRepository;
    @Mock InventoryLotRepository inventoryLotRepository;
    @Mock UserRepository userRepository;
    @Mock PumpExpenseRepository expenseRepository;
    @Mock CreditInterestChargeRepository interestChargeRepository;
    @Mock CreditClientRepository creditClientRepository;
    @Mock TankerDeliveryRepository tankerDeliveryRepository;

    @InjectMocks ReportService reportService;

    private InventoryLot lot;
    private TankerDelivery delivery;

    @BeforeEach
    void setUp() {
        lot = new InventoryLot();
        lot.setId(1L);
        lot.setTankId(10L);
        lot.setPumpId(99L);
        lot.setFuelType(FuelType.PETROL);
        lot.setDeliveryDate(OffsetDateTime.now());
        lot.setOriginalQuantity(new BigDecimal("20000"));
        lot.setRemainingQuantity(new BigDecimal("18000"));
        lot.setCostPricePerUnit(new BigDecimal("80.00"));
        lot.setStatus(LotStatus.ACTIVE);
        lot.setIsDipAdjustment(false);
        lot.setTankerDeliveryId(55L);

        delivery = new TankerDelivery();
        delivery.setId(55L);
        delivery.setInvoiceReference("INV-2026-001");
    }

    @Test
    void buildInventoryLotsReport_populatesTankerReference() {
        when(inventoryLotRepository.findAllByTankIdOrdered(10L)).thenReturn(List.of(lot));
        when(lotConsumptionRepository.findByLotIdIn(List.of(1L))).thenReturn(List.of());
        when(tankerDeliveryRepository.findAllById(java.util.Set.of(55L))).thenReturn(List.of(delivery));

        ReportService.InventoryLotsReport report = reportService.buildInventoryLotsReport(10L);

        assertThat(report.lots()).hasSize(1);
        assertThat(report.lots().get(0).tankerReference()).isEqualTo("INV-2026-001");
    }

    @Test
    void buildInventoryLotsReport_dipAdjustmentLot_usesDipAdjustmentLabel() {
        lot.setIsDipAdjustment(true);
        lot.setTankerDeliveryId(null);

        when(inventoryLotRepository.findAllByTankIdOrdered(10L)).thenReturn(List.of(lot));
        when(lotConsumptionRepository.findByLotIdIn(List.of(1L))).thenReturn(List.of());

        ReportService.InventoryLotsReport report = reportService.buildInventoryLotsReport(10L);

        assertThat(report.lots().get(0).tankerReference()).isEqualTo("DIP Adjustment");
    }
}
