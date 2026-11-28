package com.ppms.fleet;

import com.ppms.common.exception.BusinessException;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.VehicleCheckMode;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.support.FixedBusinessClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleCreditCheckTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate SALE_DATE = LocalDate.of(2026, 3, 6);

    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final ClientVehicleRepository vehicleRepository = mock(ClientVehicleRepository.class);
    private final VehicleUsageService usageService = mock(VehicleUsageService.class);
    private final NotificationDedupService notificationDedupService = mock(NotificationDedupService.class);

    private final VehicleCreditCheck check = new VehicleCreditCheck(new FleetAccountService(clientRepository),
            vehicleRepository, usageService, notificationDedupService,
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")));

    @Test
    void blockRefusesAnEntryWithoutAVehicle() {
        CreditClient client = standalone(1L, VehicleCheckMode.BLOCK);

        assertThatThrownBy(() -> checkFuel(client, input(null, null), "100.00", "1.00"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VEHICLE_REQUIRED_FOR_CREDIT");
    }

    @Test
    void blockRefusesARegistrationThatIsNotOnTheApprovedList() {
        CreditClient client = standalone(1L, VehicleCheckMode.BLOCK);
        givenVehicles(List.of(1L), vehicle(10L, 1L, "MH12AB1234"));

        assertThatThrownBy(() -> checkFuel(client, input(null, "KA01ZZ9999"), "100.00", "1.00"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VEHICLE_NOT_APPROVED");
    }

    @Test
    void blockRefusesAFuelTheVehicleMayNotTake() {
        CreditClient client = standalone(1L, VehicleCheckMode.BLOCK);
        ClientVehicle dieselOnly = vehicle(10L, 1L, "MH12AB1234");
        dieselOnly.setAllowedFuelTypes(new String[] {"DIESEL"});
        givenVehicles(List.of(1L), dieselOnly);

        assertThatThrownBy(() -> checkFuel(client, input(10L, null), "100.00", "1.00"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VEHICLE_FUEL_NOT_ALLOWED");
    }

    @Test
    void blockRefusesAnEntryPastTheMonthlyLitreLimitAndSaysWhatIsLeft() {
        CreditClient client = standalone(1L, VehicleCheckMode.BLOCK);
        ClientVehicle limited = vehicle(10L, 1L, "MH14CD5678");
        limited.setMonthlyLitreLimit(new BigDecimal("10.00"));
        limited.setMonthlyAmountLimit(new BigDecimal("1500.00"));
        givenVehicles(List.of(1L), limited);
        givenUsedThisMonth(10L, "6.00", "600.00");

        assertThatThrownBy(() -> checkFuel(client, input(10L, null), "500.00", "5.00"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException refusal = (BusinessException) error;
                    assertThat(refusal.getCode()).isEqualTo("VEHICLE_MONTHLY_LITRE_LIMIT_EXCEEDED");
                    assertThat(refusal.getParams()).containsEntry("used", "6.00").containsEntry("left", "4.00");
                });
    }

    @Test
    void typedRegistrationIsMatchedToTheApprovedVehicleAndPassesWithinItsLimits() {
        CreditClient client = standalone(1L, VehicleCheckMode.BLOCK);
        ClientVehicle limited = vehicle(10L, 1L, "MH14CD5678");
        limited.setMonthlyLitreLimit(new BigDecimal("10.00"));
        limited.setMonthlyAmountLimit(new BigDecimal("1500.00"));
        givenVehicles(List.of(1L), limited);
        givenUsedThisMonth(10L, "6.00", "600.00");

        VehicleCreditDecision decision = checkFuel(client, input(null, "mh 14 cd 5678"), "400.00", "4.00");

        assertThat(decision.vehicleId()).isEqualTo(10L);
        assertThat(decision.registration()).isEqualTo("MH14CD5678");
        assertThat(decision.flag()).isNull();
    }

    @Test
    void blockCountsEntriesOfTheSameRequestAgainstTheMonthlyRupeeLimit() {
        CreditClient client = standalone(1L, VehicleCheckMode.BLOCK);
        ClientVehicle limited = vehicle(10L, 1L, "MH12AB1234");
        limited.setMonthlyAmountLimit(new BigDecimal("500.00"));
        givenVehicles(List.of(1L), limited);
        when(usageService.monthUsageByVehicleId(any(), eq(YearMonth.from(SALE_DATE)))).thenReturn(Map.of());
        PendingVehicleUsage pending = new PendingVehicleUsage();

        check.check(client, input(10L, null), "PETROL", new BigDecimal("300.00"), new BigDecimal("3.00"), SALE_DATE,
                pending);

        assertThatThrownBy(() -> check.check(client, input(10L, null), "PETROL", new BigDecimal("300.00"),
                new BigDecimal("3.00"), SALE_DATE, pending))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VEHICLE_MONTHLY_AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    void warnLetsAnUnknownVehicleAndAWrongFuelThroughWithAFlagAndNotifiesTheManagers() {
        CreditClient client = standalone(1L, VehicleCheckMode.WARN);
        ClientVehicle dieselOnly = vehicle(10L, 1L, "MH12AB1234");
        dieselOnly.setAllowedFuelTypes(new String[] {"DIESEL"});
        givenVehicles(List.of(1L), dieselOnly);

        VehicleCreditDecision unknown = checkFuel(client, input(null, "KA01ZZ9999"), "100.00", "1.00");
        VehicleCreditDecision wrongFuel = checkFuel(client, input(10L, null), "100.00", "1.00");

        assertThat(unknown.flag()).isEqualTo(VehicleFlag.UNKNOWN_VEHICLE);
        assertThat(unknown.registration()).isEqualTo("KA01ZZ9999");
        assertThat(wrongFuel.flag()).isEqualTo(VehicleFlag.FUEL_NOT_ALLOWED);
        verify(notificationDedupService, times(2)).maybeInsert(eq(PUMP_ID), eq(NotificationType.VEHICLE_CREDIT_FLAGGED),
                eq("VEHICLE_CREDIT_FLAGGED:client:1:2026-03-06"), anyString(), anyString(), anyMap());
    }

    @Test
    void offTakesAnyOrNoVehicleWithoutAFlagOrNotification() {
        CreditClient client = standalone(1L, VehicleCheckMode.OFF);
        givenVehicles(List.of(1L), vehicle(10L, 1L, "MH12AB1234"));

        VehicleCreditDecision typedIn = checkFuel(client, input(null, "ka 01 zz 9999"), "100.00", "1.00");
        VehicleCreditDecision none = checkFuel(client, input(null, null), "100.00", "1.00");

        assertThat(typedIn.registration()).isEqualTo("KA01ZZ9999");
        assertThat(typedIn.vehicleId()).isNull();
        assertThat(typedIn.flag()).isNull();
        assertThat(none.flag()).isNull();
        verify(notificationDedupService, never()).maybeInsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void subAccountMayUseItsParentsVehicleButNotItsSiblings() {
        CreditClient parent = standalone(1L, VehicleCheckMode.BLOCK);
        CreditClient child = subAccount(2L, 1L);
        CreditClient sibling = subAccount(3L, 1L);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(clientRepository.findByParentClientId(1L)).thenReturn(List.of(child, sibling));
        when(vehicleRepository.findByIdAndPumpId(10L, PUMP_ID)).thenReturn(Optional.of(vehicle(10L, 1L, "MH12AB1234")));
        when(vehicleRepository.findByIdAndPumpId(30L, PUMP_ID)).thenReturn(Optional.of(vehicle(30L, 3L, "MH14CD5678")));

        assertThat(checkFuel(child, input(10L, null), "100.00", "1.00").vehicleId()).isEqualTo(10L);
        assertThatThrownBy(() -> checkFuel(child, input(30L, null), "100.00", "1.00"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VEHICLE_NOT_ON_ACCOUNT");
    }

    @Test
    void approvedVehicleFillsInItsDefaultDriver() {
        CreditClient client = standalone(1L, VehicleCheckMode.OFF);
        ClientVehicle vehicle = vehicle(10L, 1L, "MH12AB1234");
        vehicle.setDefaultDriverName("Ravi");
        vehicle.setDefaultDriverPhone("9876543210");
        givenVehicles(List.of(1L), vehicle);

        VehicleCreditDecision decision = checkFuel(client, input(10L, null), "100.00", "1.00");

        assertThat(decision.driverName()).isEqualTo("Ravi");
        assertThat(decision.driverPhone()).isEqualTo("9876543210");
    }

    private VehicleCreditDecision checkFuel(CreditClient client, VehicleCreditInput input, String amount, String litres) {
        return check.check(client, input, "PETROL", new BigDecimal(amount), new BigDecimal(litres), SALE_DATE,
                new PendingVehicleUsage());
    }

    private void givenVehicles(List<Long> ownerIds, ClientVehicle... vehicles) {
        when(vehicleRepository.findByClientIdInOrderByRegistrationAsc(ownerIds)).thenReturn(List.of(vehicles));
        for (ClientVehicle vehicle : vehicles) {
            when(vehicleRepository.findByIdAndPumpId(vehicle.getId(), PUMP_ID)).thenReturn(Optional.of(vehicle));
        }
    }

    private void givenUsedThisMonth(long vehicleId, String litres, String amount) {
        when(usageService.monthUsageByVehicleId(any(), eq(YearMonth.from(SALE_DATE)))).thenReturn(Map.of(vehicleId,
                new VehicleUsageGroup(vehicleId, null, new BigDecimal(litres), new BigDecimal(amount), List.of())));
    }

    private static VehicleCreditInput input(Long vehicleId, String registration) {
        return new VehicleCreditInput(vehicleId, registration, null, null);
    }

    private CreditClient standalone(long id, VehicleCheckMode mode) {
        CreditClient client = CreditClient.builder().id(id).pumpId(PUMP_ID).name("Client " + id).vehicleCheck(mode)
                .build();
        when(clientRepository.findByParentClientId(id)).thenReturn(List.of());
        return client;
    }

    private static CreditClient subAccount(long id, long parentId) {
        return CreditClient.builder().id(id).pumpId(PUMP_ID).name("Client " + id).parentClientId(parentId).build();
    }

    private static ClientVehicle vehicle(long id, long clientId, String registration) {
        return ClientVehicle.builder().id(id).pumpId(PUMP_ID).clientId(clientId).registration(registration).build();
    }
}
