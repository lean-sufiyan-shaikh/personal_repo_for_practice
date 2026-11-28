package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftHandoverServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final long INCOMING = 8L;

    @Mock private ShiftHandoverRepository handoverRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private UserRepository userRepository;
    @Mock private ShiftService shiftService;
    @Mock private ShiftReadModelService shiftReadModelService;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private ShiftOperatorRepository shiftOperatorRepository;
    @InjectMocks private ShiftHandoverService handoverService;

    private final User manager = User.builder().id(2L).role(UserRole.MANAGER).assignedPumpId(PUMP_ID).build();

    @Test
    void overdueShiftCannotBeHandedOverAndMustBeClosedInstead() {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(outgoing(ShiftStatus.OPEN_OVERDUE)));

        assertThatThrownBy(() -> handoverService.createHandover(PUMP_ID, request(new CloseShiftRequest()), manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("HANDOVER_SHIFT_OVERDUE");
                    assertThat(refused.getParams()).containsEntry("statusCode", "OPEN_OVERDUE");
                    assertThat(refused.getMessage()).contains("Close this shift and open a new shift");
                });
        verify(shiftService, never()).closeShift(any(), any(), any(), any());
    }

    @Test
    void handoverWithoutTheOutgoingOperatorsClosingFiguresIsRefused() {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(outgoing(ShiftStatus.OPEN)));
        when(userRepository.findById(INCOMING)).thenReturn(Optional.of(User.builder().id(INCOMING).fullName("Incoming")
                .role(UserRole.OPERATOR).status(UserStatus.ACTIVE).assignedPumpId(PUMP_ID).build()));

        assertThatThrownBy(() -> handoverService.createHandover(PUMP_ID, request(null), manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("HANDOVER_CLOSING_REQUIRED");
                    assertThat(refused.getMessage()).contains("closing meter readings and payments");
                });
        verify(shiftService, never()).closeShift(any(), any(), any(), any());
    }

    private static Shift outgoing(ShiftStatus status) {
        return Shift.builder().id(SHIFT_ID).pumpId(PUMP_ID).operatorId(7L).status(status).build();
    }

    private static CreateHandoverRequest request(CloseShiftRequest closing) {
        return new CreateHandoverRequest(SHIFT_ID, INCOMING, true, true, null, closing);
    }
}
