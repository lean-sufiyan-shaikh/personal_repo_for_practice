package com.ppms.common;

import com.ppms.ancillary.AncillaryInventoryWorkflowService;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillaryProductStatus;
import com.ppms.ancillary.AncillaryStockLotRepository;
import com.ppms.ancillary.RecordAncillarySaleRequest;
import com.ppms.ancillary.RecordStockDeliveryRequest;
import com.ppms.ancillary.UnitOfMeasure;
import com.ppms.common.exception.BusinessException;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.InventoryController;
import com.ppms.inventory.InventoryLot;
import com.ppms.inventory.InventoryLotRepository;
import com.ppms.inventory.LotConsumptionRepository;
import com.ppms.inventory.LotStatus;
import com.ppms.inventory.RecordDeliveryRequest;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.scheduler.OverdueShiftProcessor;
import com.ppms.shift.CloseShiftRequest;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftFuelReading;
import com.ppms.shift.ShiftFuelReadingRepository;
import com.ppms.shift.ShiftNozzle;
import com.ppms.shift.ShiftNozzleRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftService;
import com.ppms.shift.ShiftStatus;
import com.ppms.support.PostgresTestContainerConfig;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class ConcurrentUpdateIntegrationTest {

    @Autowired private ShiftService shiftService;
    @Autowired private InventoryController inventoryController;
    @Autowired private AncillaryInventoryWorkflowService ancillaryWorkflowService;
    @Autowired private OverdueShiftProcessor overdueShiftProcessor;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private DispensaryUnitRepository duRepository;
    @Autowired private NozzleRepository nozzleRepository;
    @Autowired private UndergroundTankRepository tankRepository;
    @Autowired private InventoryLotRepository inventoryLotRepository;
    @Autowired private LotConsumptionRepository lotConsumptionRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftNozzleRepository shiftNozzleRepository;
    @Autowired private ShiftFuelReadingRepository fuelReadingRepository;
    @Autowired private AncillaryProductRepository ancillaryProductRepository;
    @Autowired private AncillaryStockLotRepository ancillaryLotRepository;

    private User manager;
    private User operator;
    private PumpLocation pump;
    private UndergroundTank tank;
    private InventoryLot lot;
    private Nozzle nozzle;
    private DispensaryUnit du;

    @BeforeEach
    void createPumpWithStockedTank() {
        User owner = saveUser(UserRole.OWNER, null);
        pump = pumpLocationRepository.save(PumpLocation.builder()
                .ownerId(owner.getId())
                .name("Pump " + shortId())
                .address("Test address")
                .maxDuCount(4)
                .build());
        manager = saveUser(UserRole.MANAGER, pump.getId());
        operator = saveUser(UserRole.OPERATOR, pump.getId());
        tank = tankRepository.save(UndergroundTank.builder()
                .pumpId(pump.getId())
                .tankIdentifier("T-" + shortId())
                .fuelType(FuelType.PETROL)
                .capacity(new BigDecimal("20000"))
                .currentStock(new BigDecimal("5000.000"))
                .dipTolerance(new BigDecimal("50"))
                .status(TankStatus.ACTIVE)
                .build());
        lot = inventoryLotRepository.save(InventoryLot.builder()
                .tankId(tank.getId())
                .fuelType(FuelType.PETROL)
                .pumpId(pump.getId())
                .originalQuantity(new BigDecimal("5000.000"))
                .remainingQuantity(new BigDecimal("5000.000"))
                .costPricePerUnit(new BigDecimal("90.00"))
                .deliveryDate(OffsetDateTime.now().minusDays(1))
                .isDipAdjustment(false)
                .status(LotStatus.ACTIVE)
                .build());
        du = duRepository.save(DispensaryUnit.builder()
                .pumpId(pump.getId())
                .duNumber(1)
                .name("DU-" + shortId())
                .build());
        nozzle = nozzleRepository.save(Nozzle.builder()
                .duId(du.getId())
                .nozzleNumber(1)
                .fuelType(FuelType.PETROL)
                .lastReading(new BigDecimal("1000.000"))
                .tankId(tank.getId())
                .build());
    }

    @Test
    void closingTheSameShiftTwiceAtOnceDeductsStockOnlyOnce() throws Exception {
        Shift shift = saveOpenShift(ShiftStatus.OPEN);
        CloseShiftRequest request = closeRequestSelling("10.000");

        List<Throwable> failures = runAtTheSameTime(
                () -> shiftService.closeShift(pump.getId(), shift.getId(), request, manager),
                () -> shiftService.closeShift(pump.getId(), shift.getId(), request, manager));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(BusinessException.class).hasMessageContaining("Shift is not open");
        assertThat(tankRepository.findById(tank.getId()).orElseThrow().getCurrentStock()).isEqualByComparingTo("4990.000");
        assertThat(inventoryLotRepository.findById(lot.getId()).orElseThrow().getRemainingQuantity()).isEqualByComparingTo("4990.000");
        assertThat(lotConsumptionRepository.findByShiftId(shift.getId())).hasSize(1);
    }

    @Test
    void deliveryRecordedWhileShiftClosesIsKeptInTankStock() throws Exception {
        Shift shift = saveOpenShift(ShiftStatus.OPEN);
        RecordDeliveryRequest delivery = new RecordDeliveryRequest();
        delivery.setTankId(tank.getId());
        delivery.setQuantityDelivered(new BigDecimal("1000"));
        delivery.setCostPricePerUnit(new BigDecimal("91.00"));
        delivery.setDeliveryDate(LocalDate.now());
        delivery.setInvoiceReference("INV-" + shortId());

        List<Throwable> failures = runAtTheSameTime(
                () -> shiftService.closeShift(pump.getId(), shift.getId(), closeRequestSelling("10.000"), manager),
                () -> inventoryController.recordDelivery(pump.getId(), delivery, manager));

        assertThat(failures).isEmpty();
        assertThat(tankRepository.findById(tank.getId()).orElseThrow().getCurrentStock()).isEqualByComparingTo("5990.000");
    }

    @Test
    void twoSalesThatTogetherExceedStockCannotBothSucceed() throws Exception {
        AncillaryProduct product = ancillaryProductRepository.save(AncillaryProduct.builder()
                .pumpId(pump.getId())
                .name("Engine oil " + shortId())
                .packageSize(new BigDecimal("1.000"))
                .unitOfMeasure(UnitOfMeasure.LITRE)
                .currentStockUnits(0)
                .gstRatePercent(new BigDecimal("18.00"))
                .status(AncillaryProductStatus.ACTIVE)
                .build());
        RecordStockDeliveryRequest stock = new RecordStockDeliveryRequest();
        stock.setQuantityUnits(10);
        stock.setCostPricePerUnit(new BigDecimal("300.00"));
        stock.setDeliveryDate(LocalDate.now());
        ancillaryWorkflowService.recordDelivery(pump.getId(), product.getId(), stock, manager);

        RecordAncillarySaleRequest sale = new RecordAncillarySaleRequest();
        sale.setProductId(product.getId());
        sale.setQuantityUnits(6);
        sale.setSellingPricePerUnit(new BigDecimal("400.00"));
        sale.setPaymentMode(TransactionPaymentMode.UPI);

        List<Throwable> failures = runAtTheSameTime(
                () -> ancillaryWorkflowService.recordSale(pump.getId(), sale, manager),
                () -> ancillaryWorkflowService.recordSale(pump.getId(), sale, manager));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(BusinessException.class).hasMessageContaining("Insufficient stock");
        assertThat(ancillaryProductRepository.findById(product.getId()).orElseThrow().getCurrentStockUnits()).isEqualTo(4);
        assertThat(ancillaryLotRepository.findAllActiveLotsByProductIds(List.of(product.getId())))
                .singleElement()
                .satisfies(remainingLot -> assertThat(remainingLot.getRemainingQuantity()).isEqualTo(4));
    }

    @Test
    void overdueJobDoesNotOverwriteAShiftClosedWhileItWaited() throws Exception {
        Shift shift = saveOpenShift(ShiftStatus.OPEN_OVERDUE);
        CountDownLatch closerHoldsLock = new CountDownLatch(1);
        CountDownLatch jobStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> closer = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                Shift locked = shiftRepository.findByIdForUpdate(shift.getId()).orElseThrow();
                closerHoldsLock.countDown();
                awaitQuietly(jobStarted);
                locked.setStatus(ShiftStatus.CLOSED_BALANCED);
                locked.setCashCollected(new BigDecimal("1000.00"));
                shiftRepository.save(locked);
            }));
            closerHoldsLock.await(10, TimeUnit.SECONDS);
            Future<OverdueShiftProcessor.Outcome> job = executor.submit(() -> {
                jobStarted.countDown();
                return overdueShiftProcessor.process(shift.getId(), OffsetDateTime.now());
            });

            closer.get(30, TimeUnit.SECONDS);
            assertThat(job.get(30, TimeUnit.SECONDS)).isEqualTo(OverdueShiftProcessor.Outcome.UNCHANGED);
        } finally {
            executor.shutdownNow();
        }

        Shift reloaded = shiftRepository.findById(shift.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ShiftStatus.CLOSED_BALANCED);
        assertThat(reloaded.getCashCollected()).isEqualByComparingTo("1000.00");
    }

    private Shift saveOpenShift(ShiftStatus status) {
        Shift shift = shiftRepository.save(Shift.builder()
                .pumpId(pump.getId())
                .duId(du.getId())
                .operatorId(operator.getId())
                .openedByUserId(manager.getId())
                .shiftDate(LocalDate.now())
                .actualStartTime(OffsetDateTime.now().minusHours(2))
                .status(status)
                .isOverdueFlag(status == ShiftStatus.OPEN_OVERDUE)
                .build());
        shiftNozzleRepository.save(ShiftNozzle.builder().shiftId(shift.getId()).nozzleId(nozzle.getId()).build());
        fuelReadingRepository.save(ShiftFuelReading.builder()
                .shiftId(shift.getId())
                .nozzleId(nozzle.getId())
                .fuelType(FuelType.PETROL)
                .tankId(tank.getId())
                .startReading(new BigDecimal("1000.000"))
                .priceSnapshot(new BigDecimal("100.00"))
                .build());
        return shift;
    }

    private CloseShiftRequest closeRequestSelling(String litres) {
        BigDecimal sold = new BigDecimal(litres);
        CloseShiftRequest request = new CloseShiftRequest();
        request.setFuelReadings(List.of(new CloseShiftRequest.OutletEndReadingRequest(
                nozzle.getId(), new BigDecimal("1000.000").add(sold))));
        request.setCashCollected(sold.multiply(new BigDecimal("100.00")).setScale(2));
        request.setUpiCollected(BigDecimal.ZERO);
        request.setCardCollected(BigDecimal.ZERO);
        request.setFleetCardCollected(BigDecimal.ZERO);
        request.setCreditTotal(BigDecimal.ZERO);
        return request;
    }

    private List<Throwable> runAtTheSameTime(Callable<?> first, Callable<?> second) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new IllegalStateException("Concurrent task did not finish — possible deadlock", e);
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the shift lock", e);
        }
    }

    private User saveUser(UserRole role, Long assignedPumpId) {
        String unique = shortId();
        return userRepository.save(User.builder()
                .employeeId("EMP-" + unique)
                .fullName(role + " " + unique)
                .phoneNumber(Long.toString(6_000_000_000L + (long) (Math.random() * 3_999_999_999L)))
                .passwordHash("not-used")
                .role(role)
                .status(UserStatus.ACTIVE)
                .assignedPumpId(assignedPumpId)
                .dateOfJoining(LocalDate.now())
                .build());
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
