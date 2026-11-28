package com.ppms.onboarding;

import com.ppms.ancillary.AncillaryInventoryWorkflowService;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.UnitOfMeasure;
import com.ppms.audit.AuditService;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.TankOpeningStockService;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAndTankStockImportServiceTest {

    private static final long PUMP_ID = 7L;

    @Mock private AncillaryInventoryWorkflowService workflowService;
    @Mock private AncillaryProductRepository productRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private UndergroundTankRepository tankRepository;
    @Mock private TankOpeningStockService openingStockService;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;

    @Test
    void productProblemsDuplicatesAndExistingProductsAreReported() {
        when(productRepository.findByPumpId(PUMP_ID)).thenReturn(List.of(AncillaryProduct.builder().id(1L).pumpId(PUMP_ID)
                .name("Grease").brand("Servo").packageSize(new BigDecimal("1")).unitOfMeasure(UnitOfMeasure.KG).build()));
        ProductImportService service = new ProductImportService(workflowService, productRepository, pumpLocationRepository,
                auditService, businessClock);

        ImportReport report = service.preview(PUMP_ID, new ProductImportRequest(List.of(
                product(2, "grease", "kg", "1.000", "SERVO", null, null, null),
                product(3, "Wiper", "BOX", "-1", null, "12", "10", null),
                product(4, "Wiper", "PIECE", "1", null, null, null, null),
                product(5, "Wiper", "PIECE", "1", null, null, "2.5", "10"))));

        assertThat(report.rows()).extracting(ImportRowResult::outcome).containsExactly(
                RowOutcome.SKIP, RowOutcome.ERROR, RowOutcome.CREATE, RowOutcome.ERROR);
        assertThat(issues(report, 1)).containsExactlyInAnyOrder("unit:IMPORT_CHOICE_INVALID",
                "package_size:IMPORT_NUMBER_TOO_SMALL", "hsn_code:HSN_CODE_INVALID", "opening_cost:IMPORT_COST_REQUIRED_FOR_STOCK");
        assertThat(issues(report, 3)).containsExactlyInAnyOrder("opening_stock:IMPORT_WHOLE_NUMBER_REQUIRED",
                "name:IMPORT_DUPLICATE_IN_FILE");
    }

    @Test
    void tankStockProblemsAreReported() {
        UndergroundTank petrol = tank(1L, "Petrol Tank", TankStatus.ACTIVE);
        when(tankRepository.findByPumpId(PUMP_ID)).thenReturn(List.of(petrol, tank(2L, "Old Tank", TankStatus.INACTIVE)));

        ImportReport report = tankStockService().preview(PUMP_ID, new TankStockImportRequest(List.of(
                tankRow(2, "No Such Tank", "100", "90"),
                tankRow(3, "Petrol Tank", "20001", "abc"),
                tankRow(4, "Old Tank", "100", "90"),
                tankRow(5, "petrol tank", "100", "90"))));

        assertThat(report.rows()).extracting(ImportRowResult::outcome).containsOnly(RowOutcome.ERROR);
        assertThat(issues(report, 0)).containsExactly("tank:IMPORT_TANK_NOT_FOUND");
        assertThat(issues(report, 1)).containsExactlyInAnyOrder("litres:IMPORT_NUMBER_TOO_LARGE", "cost_per_litre:IMPORT_NUMBER_INVALID");
        assertThat(issues(report, 2)).containsExactly("tank:IMPORT_TANK_NOT_ACTIVE");
        assertThat(issues(report, 3)).containsExactly("tank:IMPORT_DUPLICATE_IN_FILE");
    }

    @Test
    void tankWithStockAlreadyIsSkipped() {
        UndergroundTank petrol = tank(1L, "Petrol Tank", TankStatus.ACTIVE);
        when(tankRepository.findByPumpId(PUMP_ID)).thenReturn(List.of(petrol));
        when(openingStockService.hasStock(petrol)).thenReturn(true);

        ImportReport report = tankStockService().preview(PUMP_ID,
                new TankStockImportRequest(List.of(tankRow(2, "Petrol Tank", "100", "90"))));

        assertThat(report.skipCount()).isEqualTo(1);
        assertThat(issues(report, 0)).containsExactly("tank:IMPORT_TANK_ALREADY_HAS_STOCK");
    }

    private TankStockImportService tankStockService() {
        return new TankStockImportService(tankRepository, openingStockService, closedDayGuard, auditService, businessClock);
    }

    private static UndergroundTank tank(long id, String identifier, TankStatus status) {
        return UndergroundTank.builder().id(id).pumpId(PUMP_ID).tankIdentifier(identifier).fuelType(FuelType.PETROL)
                .capacity(new BigDecimal("20000")).currentStock(BigDecimal.ZERO).status(status).build();
    }

    private static ProductImportRequest.Row product(int rowNumber, String name, String unit, String packageSize,
                                                    String brand, String hsnCode, String openingStock, String openingCost) {
        return new ProductImportRequest.Row(rowNumber, name, unit, packageSize, brand, null, null, hsnCode, null, null,
                openingStock, openingCost);
    }

    private static TankStockImportRequest.Row tankRow(int rowNumber, String tank, String litres, String cost) {
        return new TankStockImportRequest.Row(rowNumber, tank, litres, cost);
    }

    private static List<String> issues(ImportReport report, int rowIndex) {
        return report.rows().get(rowIndex).issues().stream()
                .map(issue -> issue.column() + ":" + issue.code())
                .toList();
    }
}
