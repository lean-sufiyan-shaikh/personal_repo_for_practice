package com.ppms.pump;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.fuel.FuelType;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TankServiceTest {

    private static final long PUMP_ID = 7L;
    private static final long TANK_ID = 31L;

    @Mock private UndergroundTankRepository tankRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private AuditService auditService;

    @InjectMocks private TankService tankService;

    private final User admin = User.builder().id(4L).role(UserRole.ADMIN).build();

    @Test
    void tankCannotBeSetToTheStatusItAlreadyHas() {
        when(tankRepository.findByIdForUpdate(TANK_ID)).thenReturn(Optional.of(activeTank()));

        assertStatusRefused(TankStatus.ACTIVE, ErrorCodes.TANK_ALREADY_IN_STATUS);
    }

    @Test
    void tankCannotBeDecommissionedThroughTheStatusChange() {
        when(tankRepository.findByIdForUpdate(TANK_ID)).thenReturn(Optional.of(activeTank()));

        assertStatusRefused(TankStatus.DECOMMISSIONED, ErrorCodes.TANK_DECOMMISSION_NEEDS_ENDPOINT);
    }

    @Test
    void disablingATankKeepsItsStock() {
        UndergroundTank tank = activeTank();
        when(tankRepository.findByIdForUpdate(TANK_ID)).thenReturn(Optional.of(tank));
        when(tankRepository.save(tank)).thenReturn(tank);

        TankResponse response = tankService.updateTankStatus(PUMP_ID, TANK_ID, TankStatus.INACTIVE, admin);

        assertThat(response.getStatus()).isEqualTo("INACTIVE");
        assertThat(tank.getCurrentStock()).isEqualByComparingTo("5000");
    }

    @Test
    void tankOfAnotherPumpIsNotFound() {
        UndergroundTank tank = activeTank();
        tank.setPumpId(99L);
        when(tankRepository.findByIdForUpdate(TANK_ID)).thenReturn(Optional.of(tank));

        assertThatThrownBy(() -> tankService.updateTankStatus(PUMP_ID, TANK_ID, TankStatus.INACTIVE, admin))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(tank.getStatus()).isEqualTo(TankStatus.ACTIVE);
    }

    private void assertStatusRefused(TankStatus status, String code) {
        assertThatThrownBy(() -> tankService.updateTankStatus(PUMP_ID, TANK_ID, status, admin))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(code);
        verify(tankRepository, never()).save(any());
    }

    private static UndergroundTank activeTank() {
        return UndergroundTank.builder().id(TANK_ID).pumpId(PUMP_ID).tankIdentifier("T1").fuelType(FuelType.PETROL)
                .capacity(new BigDecimal("20000")).currentStock(new BigDecimal("5000")).status(TankStatus.ACTIVE).build();
    }
}
