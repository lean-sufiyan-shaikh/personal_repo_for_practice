package com.ppms.fleet;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.VehicleCheckMode;
import com.ppms.support.FixedBusinessClock;
import com.ppms.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientVehicleServiceTest {

    private static final long PUMP_ID = 7L;
    private static final YearMonth THIS_MONTH = YearMonth.of(2026, 3);

    private final CreditClientAccessService clientAccessService = mock(CreditClientAccessService.class);
    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final ClientVehicleRepository vehicleRepository = mock(ClientVehicleRepository.class);
    private final VehicleUsageService usageService = mock(VehicleUsageService.class);

    private final ClientVehicleService service = new ClientVehicleService(clientAccessService, clientRepository,
            new FleetAccountService(clientRepository), vehicleRepository, usageService, mock(AuditService.class),
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")));

    @Test
    void vehicleCheckOfASubAccountIsRefusedBecauseItIsSetOnTheParent() {
        CreditClient child = client(2L, 1L, VehicleCheckMode.OFF);
        when(clientAccessService.requireClientForPump(PUMP_ID, 2L)).thenReturn(child);

        assertThatThrownBy(() -> service.setVehicleCheck(PUMP_ID, 2L,
                new UpdateVehicleCheckRequest(VehicleCheckMode.BLOCK), new User()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VEHICLE_CHECK_ON_SUB_ACCOUNT");
    }

    @Test
    void subAccountPickerOffersItsOwnAndItsParentsActiveVehiclesUnderTheParentsCheck() {
        CreditClient parent = client(1L, null, VehicleCheckMode.BLOCK);
        CreditClient child = client(2L, 1L, VehicleCheckMode.OFF);
        when(clientAccessService.requireClientForPump(PUMP_ID, 2L)).thenReturn(child);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(clientRepository.findByParentClientId(1L)).thenReturn(List.of(child, client(3L, 1L, VehicleCheckMode.OFF)));
        ClientVehicle retired = vehicle(11L, 2L, "MH14CD5678");
        retired.setActive(false);
        when(vehicleRepository.findByClientIdInOrderByRegistrationAsc(List.of(2L, 1L)))
                .thenReturn(List.of(vehicle(10L, 1L, "MH12AB1234"), retired));
        when(usageService.monthUsageByVehicleId(any(), eq(THIS_MONTH))).thenReturn(Map.of());

        ClientVehiclesResponse picker = service.vehiclesOf(PUMP_ID, 2L, true);

        assertThat(picker.vehicleCheck()).isEqualTo(VehicleCheckMode.BLOCK);
        assertThat(picker.checkClientId()).isEqualTo(1L);
        assertThat(picker.vehicles()).extracting(ClientVehicleResponse::id).containsExactly(10L);
    }

    @Test
    void parentListsTheVehiclesOfEveryAccountOfItsGroup() {
        CreditClient parent = client(1L, null, VehicleCheckMode.OFF);
        when(clientAccessService.requireClientForPump(PUMP_ID, 1L)).thenReturn(parent);
        when(clientRepository.findByParentClientId(1L))
                .thenReturn(List.of(client(2L, 1L, VehicleCheckMode.OFF), client(3L, 1L, VehicleCheckMode.OFF)));
        when(vehicleRepository.findByClientIdInOrderByRegistrationAsc(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(vehicle(10L, 1L, "MH12AB1234"), vehicle(30L, 3L, "MH14CD5678")));
        when(usageService.monthUsageByVehicleId(any(), eq(THIS_MONTH))).thenReturn(Map.of());

        assertThat(service.vehiclesOf(PUMP_ID, 1L, false).vehicles()).extracting(ClientVehicleResponse::id)
                .containsExactly(10L, 30L);
    }

    @Test
    void pickerShowsWhatIsLeftOfALimitThisMonthAndNoFigureForAMissingLimit() {
        CreditClient client = client(1L, null, VehicleCheckMode.OFF);
        when(clientAccessService.requireClientForPump(PUMP_ID, 1L)).thenReturn(client);
        when(clientRepository.findByParentClientId(1L)).thenReturn(List.of());
        ClientVehicle limited = vehicle(10L, 1L, "MH12AB1234");
        limited.setMonthlyLitreLimit(new BigDecimal("50.00"));
        when(vehicleRepository.findByClientIdInOrderByRegistrationAsc(List.of(1L))).thenReturn(List.of(limited));
        when(usageService.monthUsageByVehicleId(any(), eq(THIS_MONTH))).thenReturn(Map.of(10L,
                new VehicleUsageGroup(10L, "MH12AB1234", new BigDecimal("12.50"), new BigDecimal("1250.00"), List.of())));

        ClientVehicleResponse offered = service.vehiclesOf(PUMP_ID, 1L, true).vehicles().get(0);

        assertThat(offered.litresThisMonth()).isEqualByComparingTo("12.50");
        assertThat(offered.litresLeft()).isEqualByComparingTo("37.50");
        assertThat(offered.amountLeft()).isNull();
    }

    @Test
    void registrationThatIsNotAVehicleNumberIsRefused() {
        when(clientAccessService.requireClientForPump(PUMP_ID, 1L)).thenReturn(client(1L, null, VehicleCheckMode.OFF));

        assertThatThrownBy(() -> service.addVehicle(PUMP_ID, 1L,
                new SaveVehicleRequest("12", null, null, null, null, null), new User()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("VEHICLE_REGISTRATION_INVALID");
    }

    private static CreditClient client(long id, Long parentId, VehicleCheckMode mode) {
        return CreditClient.builder().id(id).pumpId(PUMP_ID).name("Client " + id).parentClientId(parentId)
                .vehicleCheck(mode).build();
    }

    private static ClientVehicle vehicle(long id, long clientId, String registration) {
        return ClientVehicle.builder().id(id).pumpId(PUMP_ID).clientId(clientId).registration(registration).build();
    }
}
