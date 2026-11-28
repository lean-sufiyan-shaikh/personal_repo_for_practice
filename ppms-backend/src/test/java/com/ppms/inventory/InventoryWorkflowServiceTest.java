package com.ppms.inventory;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.fuel.FuelType;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryWorkflowServiceTest {

    private static final Long PUMP_ID = 1L;
    private static final Long TANK_ID = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Mock private UndergroundTankRepository tankRepository;
    @Mock private TankerDeliveryRepository tankerDeliveryRepository;
    @Mock private InventoryLotRepository inventoryLotRepository;
    @Mock private DipCheckRepository dipCheckRepository;
    @Mock private DipCheckNozzleReadingRepository dipCheckNozzleReadingRepository;
    @Mock private LotConsumptionRepository lotConsumptionRepository;
    @Mock private TankLotConsumptionService tankLotConsumptionService;
    @Mock private UserRepository userRepository;
    @Mock private BusinessClock businessClock;
    @Mock private OpenShiftMeterService openShiftMeterService;
    @Mock private DeliveryReceiptService deliveryReceiptService;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private BalanceSheetStalenessService balanceSheetStalenessService;
    @Mock private InventoryResponseAssembler responseAssembler;
    @InjectMocks private InventoryWorkflowService workflowService;

    private final User owner = User.builder().id(10L).role(UserRole.OWNER).assignedPumpId(PUMP_ID).build();
    private final User manager = User.builder().id(11L).role(UserRole.MANAGER).assignedPumpId(PUMP_ID).build();
    private UndergroundTank tank;

    @BeforeEach
    void tankHoldingAThousandLitres() {
        tank = UndergroundTank.builder()
                .id(TANK_ID)
                .pumpId(PUMP_ID)
                .tankIdentifier("T-1")
                .fuelType(FuelType.PETROL)
                .capacity(new BigDecimal("20000"))
                .currentStock(new BigDecimal("1000"))
                .dipTolerance(new BigDecimal("50"))
                .status(TankStatus.ACTIVE)
                .build();
        when(tankRepository.findByIdForUpdate(TANK_ID)).thenReturn(Optional.of(tank));
        when(businessClock.today()).thenReturn(TODAY);
        when(businessClock.zone()).thenReturn(ZONE);
        when(businessClock.now()).thenReturn(TODAY.atTime(10, 0).atZone(ZONE).toOffsetDateTime());
        when(tankerDeliveryRepository.existsByTankId(TANK_ID)).thenReturn(true);
        when(dipCheckRepository.save(any(DipCheck.class))).thenAnswer(invocation -> {
            DipCheck check = invocation.getArgument(0);
            check.setId(50L);
            return check;
        });
    }

    @Test
    void batchDeliveryListingTheSameTankTwiceIsRefused() {
        RecordBatchDeliveryRequest request = new RecordBatchDeliveryRequest();
        request.setDeliveryDate(TODAY);
        request.setInvoiceReference("INV-1");
        request.setItems(List.of(batchLine("100"), batchLine("200")));

        assertThatThrownBy(() -> workflowService.recordBatchDelivery(PUMP_ID, request, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("BATCH_TANK_REPEATED"));
        verify(tankRepository, never()).save(any());
    }

    @Test
    void deliveryAboveTheTanksFreeCapacityIsRefused() {
        receiptMeasures("19500");

        assertThatThrownBy(() -> workflowService.recordDelivery(PUMP_ID, delivery("19500", "90"), owner))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("DELIVERY_OVERFLOWS_TANK");
                    assertThat(refusal.getParams()).containsEntry("availableCapacity", "19000.000");
                });
    }

    @Test
    void ownerMustEnterACostForASingleDelivery() {
        receiptMeasures("200");

        assertThatThrownBy(() -> workflowService.recordDelivery(PUMP_ID, delivery("200", null), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("COST_PRICE_REQUIRED"));
    }

    @Test
    void managerCannotSendAFuelCost() {
        receiptMeasures("200");

        assertThatThrownBy(() -> workflowService.recordDelivery(PUMP_ID, delivery("200", "10.00"), manager))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void managerDeliveryToATankWithNoEarlierDeliveryIsRefused() {
        receiptMeasures("200");
        when(tankerDeliveryRepository.findTopByTankIdOrderByDeliveryDateDescCreatedAtDesc(TANK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.recordDelivery(PUMP_ID, delivery("200", null), manager))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("FIRST_COST_PRICE_NEEDS_OWNER");
                    assertThat(refusal.getParams()).containsEntry("tankIdentifier", "T-1");
                    assertThat(refusal.getMessage()).contains("an owner or admin must enter the first cost");
                });
    }

    @Test
    void backDatedDipCheckIsRefused() {
        RecordDipCheckRequest request = dip("900");
        request.setCheckedAt(TODAY.minusDays(1));

        assertThatThrownBy(() -> workflowService.recordDipCheck(PUMP_ID, request, manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("DIP_CHECK_ONLY_TODAY"));
        verify(tankRepository, never()).save(any());
    }

    @Test
    void dipCheckOnAnEmptyTankThatNeverHadADeliveryIsRefused() {
        tank.setCurrentStock(BigDecimal.ZERO);
        when(tankerDeliveryRepository.existsByTankId(TANK_ID)).thenReturn(false);

        assertThatThrownBy(() -> workflowService.recordDipCheck(PUMP_ID, dip("100"), manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("TANK_HAS_NO_DELIVERY"));
    }

    @Test
    void dipCheckNamingStaffOfAnotherPumpIsRefused() {
        RecordDipCheckRequest request = dip("990");
        request.setCheckedByUserId(99L);
        when(userRepository.findById(99L)).thenReturn(Optional.of(
                User.builder().id(99L).role(UserRole.OPERATOR).assignedPumpId(2L).build()));

        assertThatThrownBy(() -> workflowService.recordDipCheck(PUMP_ID, request, manager))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tankRepository, never()).save(any());
    }

    @Test
    void dipCheckGainWithinToleranceAddsStockAtTheLatestPurchaseCostWithoutReview() {
        when(inventoryLotRepository.findActiveLotsByTankForDisplay(TANK_ID)).thenReturn(List.of(InventoryLot.builder()
                .costPricePerUnit(new BigDecimal("90.00")).isDipAdjustment(false).build()));

        workflowService.recordDipCheck(PUMP_ID, dip("1020"), manager);

        assertThat(savedDipCheck().getStatus()).isEqualTo(DipCheckStatus.WITHIN_TOLERANCE);
        assertThat(tank.getCurrentStock()).isEqualByComparingTo("1020");
        ArgumentCaptor<InventoryLot> lot = ArgumentCaptor.forClass(InventoryLot.class);
        verify(inventoryLotRepository).save(lot.capture());
        assertThat(lot.getValue().getRemainingQuantity()).isEqualByComparingTo("20");
        assertThat(lot.getValue().getCostPricePerUnit()).isEqualByComparingTo("90.00");
        assertThat(lot.getValue().getIsDipAdjustment()).isTrue();
    }

    @Test
    void dipCheckShortageBeyondToleranceSetsStockToTheMeasuredQuantityAndNeedsReview() {
        workflowService.recordDipCheck(PUMP_ID, dip("900"), manager);

        assertThat(savedDipCheck().getStatus()).isEqualTo(DipCheckStatus.PENDING_REVIEW);
        assertThat(tank.getCurrentStock()).isEqualByComparingTo("900");
    }

    private DipCheck savedDipCheck() {
        ArgumentCaptor<DipCheck> check = ArgumentCaptor.forClass(DipCheck.class);
        verify(dipCheckRepository).save(check.capture());
        return check.getValue();
    }

    private void receiptMeasures(String receivedLitres) {
        when(deliveryReceiptService.measure(any(), any())).thenReturn(new DeliveryReceiptService.MeasuredReceipt(
                BigDecimal.ZERO, new BigDecimal(receivedLitres), BigDecimal.ZERO, new BigDecimal(receivedLitres),
                null, null, List.of()));
    }

    private static RecordDeliveryRequest delivery(String litres, String costPerLitre) {
        RecordDeliveryRequest request = new RecordDeliveryRequest();
        request.setTankId(TANK_ID);
        request.setQuantityDelivered(new BigDecimal(litres));
        request.setCostPricePerUnit(costPerLitre != null ? new BigDecimal(costPerLitre) : null);
        request.setDeliveryDate(TODAY);
        request.setInvoiceReference("INV-1");
        return request;
    }

    private static RecordBatchDeliveryRequest.LineItem batchLine(String litres) {
        RecordBatchDeliveryRequest.LineItem line = new RecordBatchDeliveryRequest.LineItem();
        line.setTankId(TANK_ID);
        line.setQuantityDelivered(new BigDecimal(litres));
        line.setCostPricePerUnit(new BigDecimal("91"));
        return line;
    }

    private static RecordDipCheckRequest dip(String measuredLitres) {
        RecordDipCheckRequest request = new RecordDipCheckRequest();
        request.setTankId(TANK_ID);
        request.setMeasuredQuantity(new BigDecimal(measuredLitres));
        return request;
    }
}
