package com.ppms.demo;

import com.ppms.ancillary.AncillaryInventoryWorkflowService;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductPrice;
import com.ppms.ancillary.AncillaryProductPriceRepository;
import com.ppms.ancillary.CreateProductRequest;
import com.ppms.ancillary.RecordStockDeliveryRequest;
import com.ppms.ancillary.UnitOfMeasure;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditOpeningBalance;
import com.ppms.credit.CreditOpeningBalanceRepository;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.inventory.TankOpeningStockService;
import com.ppms.onboarding.TemporaryPasswordGenerator;
import com.ppms.pump.CreateTankRequest;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpShiftDefinition;
import com.ppms.pump.PumpShiftDefinitionRepository;
import com.ppms.pump.TankService;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.user.User;
import com.ppms.user.UserGender;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Sets the demo pump up as an owner would: tanks with opening stock, two dispensers, day and night shifts, fuel prices,
 * staff, credit clients and counter products, all dated so the sample activity can start {@link #START_DAYS_AGO} days ago.
 */
@Component
@RequiredArgsConstructor
class DemoPumpBuilder {

    static final int START_DAYS_AGO = 15;
    static final BigDecimal PETROL_PRICE = new BigDecimal("104.21");
    static final BigDecimal PETROL_PRICE_AFTER_REVISION = new BigDecimal("104.61");
    static final BigDecimal DIESEL_PRICE = new BigDecimal("90.76");
    static final int PRICE_REVISION_DAYS_AGO = 6;

    private final TankService tankService;
    private final UndergroundTankRepository tankRepository;
    private final TankOpeningStockService openingStockService;
    private final DispensaryUnitRepository duRepository;
    private final NozzleRepository nozzleRepository;
    private final PumpShiftDefinitionRepository shiftDefinitionRepository;
    private final GlobalFuelPriceRepository fuelPriceRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator passwordGenerator;
    private final CreditClientRepository creditClientRepository;
    private final CreditOpeningBalanceRepository openingBalanceRepository;
    private final AncillaryInventoryWorkflowService ancillaryWorkflowService;
    private final AncillaryProductPriceRepository productPriceRepository;
    private final BusinessClock businessClock;

    DemoPumpLayout build(PumpLocation pump, User actor) {
        LocalDate start = businessClock.today().minusDays(START_DAYS_AGO);
        UndergroundTank petrolTank = addTank(pump, "Tank 1 - Petrol", FuelType.PETROL, "15000", actor);
        UndergroundTank dieselTank = addTank(pump, "Tank 2 - Diesel", FuelType.DIESEL, "20000", actor);
        openingStockService.addOpeningStock(petrolTank, new BigDecimal("9000"), new BigDecimal("96.80"), businessClock.startOfDay(start));
        openingStockService.addOpeningStock(dieselTank, new BigDecimal("12000"), new BigDecimal("84.10"), businessClock.startOfDay(start));

        List<DemoPumpLayout.DemoDispenser> dispensers = List.of(
                addDispenser(pump, 1, petrolTank, dieselTank, "125400.000", "98210.000"),
                addDispenser(pump, 2, petrolTank, dieselTank, "86120.000", "143875.000"));
        PumpShiftDefinition dayShift = addShift(pump, "Day", LocalTime.of(6, 0), LocalTime.of(18, 0), false, 1, start, actor);
        PumpShiftDefinition nightShift = addShift(pump, "Night", LocalTime.of(18, 0), LocalTime.of(6, 0), true, 2, start, actor);
        addFuelPrices(pump, start, actor);

        User manager = addStaff(pump, 1, "Demo Manager", UserRole.MANAGER, start);
        List<User> dayOperators = List.of(addStaff(pump, 2, "Ravi (demo)", UserRole.OPERATOR, start),
                addStaff(pump, 3, "Suresh (demo)", UserRole.OPERATOR, start));
        List<User> nightOperators = List.of(addStaff(pump, 4, "Amit (demo)", UserRole.OPERATOR, start),
                addStaff(pump, 5, "Prakash (demo)", UserRole.OPERATOR, start));

        CreditClient fleet = addClient(pump, "Sharma Transport (demo)", 51, "50000", null);
        CreditClient fleetSub = addClient(pump, "Sharma Transport - Tankers", 51, "0", fleet.getId());
        CreditClient farm = addClient(pump, "Patil Farms (demo)", 52, "20000", null);
        CreditClient busDepot = addClient(pump, "City Bus Depot (demo)", 53, "100000", null);
        openingBalanceRepository.save(CreditOpeningBalance.builder()
                .pumpId(pump.getId()).clientId(busDepot.getId()).amount(new BigDecimal("12500.00"))
                .asOfDate(start).createdByUserId(actor.getId()).build());

        List<AncillaryProduct> products = List.of(
                addProduct(pump, "Engine Oil 20W-40", "Castrol", "420.00", "330.00", 40, start, actor),
                addProduct(pump, "Coolant", "Prestone", "280.00", "210.00", 30, start, actor),
                addProduct(pump, "Distilled Water", null, "40.00", "22.00", 60, start, actor));

        return new DemoPumpLayout(pump, petrolTank, dieselTank, dispensers, dayShift, nightShift, manager,
                dayOperators, nightOperators, fleet, fleetSub, farm, busDepot, products);
    }

    private UndergroundTank addTank(PumpLocation pump, String identifier, FuelType fuelType, String capacity, User actor) {
        CreateTankRequest request = new CreateTankRequest();
        request.setTankIdentifier(identifier);
        request.setFuelType(fuelType);
        request.setCapacity(new BigDecimal(capacity));
        Long tankId = tankService.createTank(pump.getId(), request, actor).getId();
        return tankRepository.findById(tankId).orElseThrow();
    }

    private DemoPumpLayout.DemoDispenser addDispenser(PumpLocation pump, int number, UndergroundTank petrolTank,
                                                      UndergroundTank dieselTank, String petrolReading, String dieselReading) {
        DispensaryUnit du = duRepository.save(DispensaryUnit.builder()
                .pumpId(pump.getId()).duNumber(number).name("DU " + number).build());
        Nozzle petrol = nozzleRepository.save(Nozzle.builder().duId(du.getId()).nozzleNumber(1).fuelType(FuelType.PETROL)
                .lastReading(new BigDecimal(petrolReading)).tankId(petrolTank.getId()).build());
        Nozzle diesel = nozzleRepository.save(Nozzle.builder().duId(du.getId()).nozzleNumber(2).fuelType(FuelType.DIESEL)
                .lastReading(new BigDecimal(dieselReading)).tankId(dieselTank.getId()).build());
        return new DemoPumpLayout.DemoDispenser(du, petrol, diesel);
    }

    private PumpShiftDefinition addShift(PumpLocation pump, String name, LocalTime start, LocalTime end, boolean night,
                                         int sortOrder, LocalDate effectiveFrom, User actor) {
        return shiftDefinitionRepository.save(PumpShiftDefinition.builder()
                .pumpId(pump.getId()).name(name).startTime(start).endTime(end)
                .crossesMidnight(night).isNightShift(night).sortOrder(sortOrder)
                .effectiveFrom(effectiveFrom).createdByUserId(actor.getId()).build());
    }

    private void addFuelPrices(PumpLocation pump, LocalDate start, User actor) {
        LocalDate today = businessClock.today();
        LocalDate revision = today.minusDays(PRICE_REVISION_DAYS_AGO);
        fuelPriceRepository.saveAll(List.of(
                fuelPrice(pump, FuelType.PETROL, PETROL_PRICE, start, actor),
                fuelPrice(pump, FuelType.DIESEL, DIESEL_PRICE, start, actor),
                fuelPrice(pump, FuelType.PETROL, PETROL_PRICE_AFTER_REVISION, revision, actor),
                fuelPrice(pump, FuelType.PETROL, PETROL_PRICE_AFTER_REVISION, today, actor),
                fuelPrice(pump, FuelType.DIESEL, DIESEL_PRICE, today, actor)));
    }

    private GlobalFuelPrice fuelPrice(PumpLocation pump, FuelType fuelType, BigDecimal price, LocalDate from, User actor) {
        return GlobalFuelPrice.builder().pumpId(pump.getId()).fuelType(fuelType).pricePerUnit(price)
                .effectiveFrom(businessClock.startOfDay(from)).setByUserId(actor.getId()).build();
    }

    /** Demo staff sign in with a phone number no real mobile has (it starts with 0) and a password nobody is told. */
    private User addStaff(PumpLocation pump, int number, String name, UserRole role, LocalDate joined) {
        boolean operator = role == UserRole.OPERATOR;
        return userRepository.save(User.builder()
                .employeeId("DEMO-" + pump.getId() + "-" + number)
                .fullName(name)
                .phoneNumber(demoPhone(pump, number))
                .passwordHash(passwordEncoder.encode(passwordGenerator.generate()))
                .gender(UserGender.MALE)
                .role(role)
                .status(UserStatus.ACTIVE)
                .assignedPumpId(pump.getId())
                .dateOfJoining(joined)
                .mustChangePassword(true)
                .dailyRate(operator ? null : new BigDecimal("900.00"))
                .standardHourlyRate(operator ? new BigDecimal("60.00") : null)
                .shift1HourlyRate(operator ? new BigDecimal("75.00") : null)
                .build());
    }

    private CreditClient addClient(PumpLocation pump, String name, int phoneNumber, String creditLimit, Long parentId) {
        return creditClientRepository.save(CreditClient.builder()
                .pumpId(pump.getId()).name(name).phone(demoPhone(pump, phoneNumber))
                .creditLimit(new BigDecimal(creditLimit)).parentClientId(parentId).build());
    }

    private AncillaryProduct addProduct(PumpLocation pump, String name, String brand, String price, String cost,
                                        int units, LocalDate start, User actor) {
        CreateProductRequest request = new CreateProductRequest();
        request.setName(name);
        request.setBrand(brand);
        request.setPackageSize(BigDecimal.ONE);
        request.setUnitOfMeasure(UnitOfMeasure.LITRE);
        request.setLowStockThreshold(5);
        request.setHsnCode("27101980");
        AncillaryProduct product = ancillaryWorkflowService.createProduct(pump.getId(), request, actor);
        productPriceRepository.save(AncillaryProductPrice.builder().productId(product.getId()).pumpId(pump.getId())
                .pricePerUnit(new BigDecimal(price)).effectiveFrom(businessClock.startOfDay(start))
                .setByUserId(actor.getId()).build());

        RecordStockDeliveryRequest delivery = new RecordStockDeliveryRequest();
        delivery.setQuantityUnits(units);
        delivery.setCostPricePerUnit(new BigDecimal(cost));
        delivery.setDeliveryDate(start);
        delivery.setInvoiceReference("DEMO-STOCK");
        ancillaryWorkflowService.recordDelivery(pump.getId(), product.getId(), delivery, actor);
        return product;
    }

    private static String demoPhone(PumpLocation pump, int number) {
        return String.format("0%07d%02d", pump.getId(), number);
    }
}
