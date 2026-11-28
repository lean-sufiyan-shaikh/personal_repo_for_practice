package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftOperatorServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final long OWNER = 7L;
    private static final long SECOND = 8L;

    @Mock private ShiftRepository shiftRepository;
    @Mock private ShiftOperatorRepository shiftOperatorRepository;
    @Mock private ShiftNozzleTurnRepository turnRepository;
    @Mock private ShiftReliefRepository reliefRepository;
    @Mock private ClosedDayGuard closedDayGuard;
    @InjectMocks private ShiftOperatorService operatorService;

    private final Shift shift = Shift.builder().id(SHIFT_ID).pumpId(PUMP_ID).operatorId(OWNER).status(ShiftStatus.OPEN).build();

    @Test
    void operatorNotWorkingTheShiftIsRefusedWithTheGivenCode() {
        User colleague = User.builder().id(SECOND).role(UserRole.OPERATOR).build();
        when(shiftOperatorRepository.findByShiftIdAndOperatorIdAndLeftAtIsNull(SHIFT_ID, SECOND)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operatorService.requireWorkingUnlessManager(shift, colleague, "BLIND_COUNT_NOT_OWN_SHIFT", "Not yours"))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("BLIND_COUNT_NOT_OWN_SHIFT"));
    }

    @Test
    void shiftsOwnOperatorAnAddedOperatorAndAManagerMayAct() {
        when(shiftOperatorRepository.findByShiftIdAndOperatorIdAndLeftAtIsNull(SHIFT_ID, SECOND))
                .thenReturn(Optional.of(ShiftOperator.builder().operatorId(SECOND).build()));

        assertThatCode(() -> {
            operatorService.requireWorkingUnlessManager(shift, User.builder().id(OWNER).role(UserRole.OPERATOR).build(), "X", "x");
            operatorService.requireWorkingUnlessManager(shift, User.builder().id(SECOND).role(UserRole.OPERATOR).build(), "X", "x");
            operatorService.requireWorkingUnlessManager(shift, User.builder().id(2L).role(UserRole.MANAGER).build(), "X", "x");
        }).doesNotThrowAnyException();
    }

    @Test
    void closedShiftCannotBeChanged() {
        shift.setStatus(ShiftStatus.CLOSED_BALANCED);
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(shift));

        assertThatThrownBy(() -> operatorService.lockOpenShift(PUMP_ID, SHIFT_ID))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo("SHIFT_NOT_OPEN"));
    }

    @Test
    void operatorWithTheirOwnOpenShiftOrWorkingAnotherCannotJoin() {
        User withOwnShift = User.builder().id(SECOND).fullName("Second").build();
        when(shiftRepository.findOpenShiftByOperator(SECOND)).thenReturn(Optional.of(Shift.builder().id(31L).build()));
        User onAnotherShift = User.builder().id(9L).fullName("Busy").build();
        when(shiftRepository.findOpenShiftByOperator(9L)).thenReturn(Optional.empty());
        when(shiftOperatorRepository.findByOperatorIdAndLeftAtIsNull(9L))
                .thenReturn(Optional.of(ShiftOperator.builder().shiftId(32L).operatorId(9L).build()));

        assertThatThrownBy(() -> operatorService.rejectOperatorOnAnotherShift(withOwnShift))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("OPERATOR_SHIFT_ALREADY_OPEN"));
        assertThatThrownBy(() -> operatorService.rejectOperatorOnAnotherShift(onAnotherShift))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getParams()).containsEntry("shiftId", 32L));
    }

    @Test
    void addedOperatorStillOnANozzleCannotLeave() {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(shift));
        when(shiftOperatorRepository.findByShiftIdAndOperatorIdAndLeftAtIsNull(SHIFT_ID, SECOND))
                .thenReturn(Optional.of(ShiftOperator.builder().shiftId(SHIFT_ID).operatorId(SECOND).build()));
        when(turnRepository.findByShiftIdAndEndedAtIsNull(SHIFT_ID))
                .thenReturn(List.of(ShiftNozzleTurn.builder().nozzleId(12L).operatorId(SECOND).build()));

        assertThatThrownBy(() -> operatorService.removeOperator(PUMP_ID, SHIFT_ID, SECOND, User.builder().id(2L).build()))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("SHIFT_OPERATOR_HOLDS_NOZZLES"));
    }
}
