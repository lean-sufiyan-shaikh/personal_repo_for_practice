package com.ppms.shift;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.pump.Nozzle;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftReliefServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final long OWNER = 7L;
    private static final long RELIEVER = 9L;
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock private ShiftReliefRepository reliefRepository;
    @Mock private ShiftOperatorRepository shiftOperatorRepository;
    @Mock private ShiftOperatorService shiftOperatorService;
    @Mock private NozzleTurnService nozzleTurnService;
    @Mock private ShiftReadModelService shiftReadModelService;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @InjectMocks private ShiftReliefService reliefService;

    private final Shift shift = Shift.builder().id(SHIFT_ID).pumpId(PUMP_ID).operatorId(OWNER).status(ShiftStatus.OPEN).build();
    private final User manager = User.builder().id(2L).role(UserRole.MANAGER).build();

    @Test
    void reliefNeedsAReadingForEveryNozzleTheOperatorHolds() {
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(shift);
        when(shiftOperatorService.isWorking(shift, OWNER)).thenReturn(true);
        when(shiftOperatorService.isWorking(shift, RELIEVER)).thenReturn(true);
        when(userRepository.findById(RELIEVER)).thenReturn(Optional.of(User.builder().id(RELIEVER).fullName("Reliever").build()));
        when(reliefRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ShiftNozzleTurn first = ShiftNozzleTurn.builder().nozzleId(11L).operatorId(OWNER).build();
        ShiftNozzleTurn second = ShiftNozzleTurn.builder().nozzleId(12L).operatorId(OWNER).build();
        when(nozzleTurnService.heldNozzles(shift, OWNER, manager)).thenReturn(List.of(first, second));
        when(nozzleTurnService.nozzleOfShift(SHIFT_ID, 11L)).thenReturn(nozzle(11L, 1));
        when(nozzleTurnService.nozzleOfShift(SHIFT_ID, 12L)).thenReturn(nozzle(12L, 2));
        StartReliefRequest onlyOneReading = new StartReliefRequest(OWNER, RELIEVER,
                List.of(new NozzleMeterReading(11L, new BigDecimal("1003.000"))), null, null);

        assertThatThrownBy(() -> reliefService.startRelief(PUMP_ID, SHIFT_ID, onlyOneReading, manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("RELIEF_READING_MISSING");
                    assertThat(refused.getParams()).containsEntry("nozzleNumber", 2);
                });
    }

    @Test
    void operatorAlreadyOnABreakCannotStartAnother() {
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(shift);
        when(shiftOperatorService.isWorking(shift, OWNER)).thenReturn(true);
        when(reliefRepository.findByShiftIdAndRelievedOperatorIdAndEndedAtIsNull(SHIFT_ID, OWNER))
                .thenReturn(Optional.of(ShiftRelief.builder().id(50L).build()));
        StartReliefRequest request = new StartReliefRequest(OWNER, 8L,
                List.of(new NozzleMeterReading(11L, new BigDecimal("1004.000"))), null, null);

        assertThatThrownBy(() -> reliefService.startRelief(PUMP_ID, SHIFT_ID, request, manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("RELIEF_ALREADY_ACTIVE"));
    }

    @Test
    void reliefThatAlreadyEndedCannotEndAgain() {
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(shift);
        when(reliefRepository.findByIdAndShiftId(50L, SHIFT_ID)).thenReturn(Optional.of(
                ShiftRelief.builder().id(50L).shiftId(SHIFT_ID).relievedOperatorId(OWNER).reliefOperatorId(RELIEVER)
                        .endedAt(NOW).build()));
        EndReliefRequest request = new EndReliefRequest(List.of(new NozzleMeterReading(11L, new BigDecimal("1008.000"))),
                null, null);

        assertThatThrownBy(() -> reliefService.endRelief(PUMP_ID, SHIFT_ID, 50L, request, manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("RELIEF_NOT_ACTIVE"));
    }

    @Test
    void endingAReliefLetsTheRelieverLeaveTheShift() {
        ShiftRelief relief = ShiftRelief.builder().id(50L).shiftId(SHIFT_ID).relievedOperatorId(OWNER)
                .reliefOperatorId(RELIEVER).startedAt(NOW.minusHours(1)).build();
        ShiftOperator relieverOnShift = ShiftOperator.builder().shiftId(SHIFT_ID).operatorId(RELIEVER)
                .role(ShiftOperatorRole.RELIEF).joinedAt(NOW.minusHours(1)).build();
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(shift);
        when(reliefRepository.findByIdAndShiftId(50L, SHIFT_ID)).thenReturn(Optional.of(relief));
        when(businessClock.now()).thenReturn(NOW);
        when(shiftOperatorRepository.findByShiftIdAndOperatorIdAndLeftAtIsNull(SHIFT_ID, RELIEVER))
                .thenReturn(Optional.of(relieverOnShift));

        reliefService.endRelief(PUMP_ID, SHIFT_ID, 50L, new EndReliefRequest(
                List.of(new NozzleMeterReading(11L, new BigDecimal("1008.000"))), new BigDecimal("700"), null), manager);

        assertThat(relief.isActive()).isFalse();
        assertThat(relief.getCashHandedBack()).isEqualByComparingTo("700.00");
        assertThat(relieverOnShift.getLeftAt()).isEqualTo(NOW);
    }

    @Test
    void reliefStillRunningAtCloseEndsWithTheShift() {
        ShiftRelief running = ShiftRelief.builder().id(50L).shiftId(SHIFT_ID).relievedOperatorId(OWNER)
                .reliefOperatorId(RELIEVER).startedAt(NOW.minusHours(1)).build();
        when(reliefRepository.findByShiftIdAndEndedAtIsNull(SHIFT_ID)).thenReturn(List.of(running));

        reliefService.endActiveReliefsAtClose(SHIFT_ID, NOW, manager);

        assertThat(running.isActive()).isFalse();
        assertThat(running.getEndedByUserId()).isEqualTo(manager.getId());
    }

    private static Nozzle nozzle(long id, int number) {
        return Nozzle.builder().id(id).nozzleNumber(number).fuelType(FuelType.PETROL)
                .maxMeterValue(new BigDecimal("99999999.999")).build();
    }
}
