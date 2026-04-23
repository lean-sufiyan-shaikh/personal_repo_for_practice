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
    void buildInventoryLotsReport_computesSummaryFields() {
        InventoryLot lot2 = new InventoryLot();
        lot2.setId(2L);
        lot2.setTankId(10L);
        lot2.setPumpId(99L);
        lot2.setFuelType(FuelType.PETROL);
        lot2.setDeliveryDate(OffsetDateTime.now());
        lot2.setOriginalQuantity(new BigDecimal("10000"));
        lot2.setRemainingQuantity(new BigDecimal("5000"));
        lot2.setCostPricePerUnit(new BigDecimal("85.00"));
        lot2.setStatus(LotStatus.ACTIVE);
        lot2.setIsDipAdjustment(false);
        lot2.setTankerDeliveryId(56L);

        TankerDelivery delivery2 = new TankerDelivery();
        delivery2.setId(56L);
        delivery2.setInvoiceReference("INV-2026-002");

        when(inventoryLotRepository.findAllByTankIdOrdered(10L)).thenReturn(List.of(lot, lot2));
        when(lotConsumptionRepository.findByLotIdIn(List.of(1L, 2L))).thenReturn(List.of());
        when(tankerDeliveryRepository.findAllById(java.util.Set.of(55L, 56L))).thenReturn(List.of(delivery, delivery2));

        ReportService.InventoryLotsReport report = reportService.buildInventoryLotsReport(10L);

        // totalRemaining = 18000 + 5000 = 23000
        assertThat(report.totalRemaining()).isEqualByComparingTo("23000.00");
        // weightedAvgCost = (18000×80 + 5000×85) / 23000 = 1865000 / 23000 ≈ 81.09
        assertThat(report.weightedAvgCostPerUnit()).isNotNull();
        // totalStockValue = 18000×80 + 5000×85 = 1865000
        assertThat(report.totalStockValue()).isEqualByComparingTo("1865000.00");
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
