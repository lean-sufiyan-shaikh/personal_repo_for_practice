package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.fuel.FuelType;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftAmendmentServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final long NOZZLE_ID = 11L;

    @Mock private ShiftRepository shiftRepository;
    @Mock private ShiftFuelReadingRepository fuelReadingRepository;
    @Mock private NozzleRepository nozzleRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private ClosedDayGuard closedDayGuard;
    @InjectMocks private ShiftAmendmentService amendmentService;

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();

    @BeforeEach
    void givenPump() {
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
    }

    @Test
    void openShiftCannotBeAmended() {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(
                Shift.builder().id(SHIFT_ID).pumpId(PUMP_ID).status(ShiftStatus.OPEN).build()));

        assertRefused(request("1012.000", "0.00", "0.00"), "SHIFT_AMEND_NOT_CLOSED");
    }

    @Test
    void amendmentWithTheSameFiguresIsRefused() {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(Shift.builder()
                .id(SHIFT_ID).pumpId(PUMP_ID).status(ShiftStatus.CLOSED_BALANCED)
                .totalAmountDue(new BigDecimal("1000.00")).cashCollected(new BigDecimal("500.00"))
                .upiCollected(new BigDecimal("500.00")).cardCollected(BigDecimal.ZERO).fleetCardCollected(BigDecimal.ZERO)
                .build()));
        when(fuelReadingRepository.findByShiftId(SHIFT_ID)).thenReturn(List.of(ShiftFuelReading.builder().shiftId(SHIFT_ID)
                .nozzleId(NOZZLE_ID).fuelType(FuelType.PETROL).startReading(new BigDecimal("1000.000"))
                .endReading(new BigDecimal("1010.000")).unitsSold(new BigDecimal("10.000"))
                .priceSnapshot(new BigDecimal("100.00")).build()));
        when(nozzleRepository.findAllById(any())).thenReturn(List.of(Nozzle.builder().id(NOZZLE_ID).nozzleNumber(1).build()));

        assertRefused(request("1010.000", "500.00", "500.00"), "SHIFT_AMEND_NO_CHANGE");
    }

    private void assertRefused(AmendShiftRequest request, String code) {
        assertThatThrownBy(() -> amendmentService.amend(PUMP_ID, SHIFT_ID, request, owner))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(shiftRepository, never()).save(any());
    }

    private static AmendShiftRequest request(String endReading, String cash, String upi) {
        return new AmendShiftRequest(List.of(new CloseShiftRequest.OutletEndReadingRequest(NOZZLE_ID, new BigDecimal(endReading))),
                new BigDecimal(cash), new BigDecimal(upi), BigDecimal.ZERO, BigDecimal.ZERO, "Nothing really");
    }
}
