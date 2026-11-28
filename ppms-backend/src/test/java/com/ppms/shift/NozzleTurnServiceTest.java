package com.ppms.shift;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NozzleTurnServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final long OWNER = 7L;
    private static final long SECOND = 8L;
    private static final long NOZZLE_ID = 12L;
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock private ShiftNozzleTurnRepository turnRepository;
    @Mock private ShiftFuelReadingRepository fuelReadingRepository;
    @Mock private NozzleRepository nozzleRepository;
    @Mock private UserRepository userRepository;
    @Mock private ShiftOperatorService shiftOperatorService;
    @Mock private ShiftReadModelService shiftReadModelService;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @InjectMocks private NozzleTurnService turnService;

    private final Shift shift = Shift.builder().id(SHIFT_ID).pumpId(PUMP_ID).operatorId(OWNER).status(ShiftStatus.OPEN).build();
    private final User manager = User.builder().id(2L).role(UserRole.MANAGER).build();

    @Test
    void swapToTheOperatorAlreadyOnTheNozzleIsRefused() {
        givenNozzleHeldBy(OWNER, null);

        assertSwapRefused(OWNER, "2005.000", "NOZZLE_SWAP_SAME_OPERATOR");
    }

    @Test
    void swapReadingBelowTheTurnsStartIsRefused() {
        givenNozzleHeldBy(OWNER, null);
        when(shiftOperatorService.isWorking(shift, SECOND)).thenReturn(true);
        when(userRepository.findById(SECOND)).thenReturn(Optional.of(User.builder().id(SECOND).fullName("Second").build()));

        assertSwapRefused(SECOND, "1999.000", "NOZZLE_READING_BELOW_LAST");
    }

    @Test
    void nozzleCoveredDuringABreakCannotBeSwapped() {
        givenNozzleHeldBy(SECOND, 50L);

        assertSwapRefused(OWNER, "2004.000", "NOZZLE_UNDER_RELIEF");
    }

    @Test
    void swapToSomeoneNotOnTheShiftAddsThemAndStartsTheirTurnAtTheReading() {
        ShiftNozzleTurn current = givenNozzleHeldBy(OWNER, null);
        User second = User.builder().id(SECOND).fullName("Second").role(UserRole.OPERATOR).build();
        when(shiftOperatorService.isWorking(shift, SECOND)).thenReturn(false);
        when(shiftOperatorService.findOperatorForPump(SECOND, PUMP_ID)).thenReturn(second);
        when(businessClock.now()).thenReturn(NOW);

        turnService.swapNozzle(PUMP_ID, SHIFT_ID, new SwapNozzleRequest(NOZZLE_ID, SECOND, new BigDecimal("2005.000"), null), manager);

        verify(shiftOperatorService).join(shift, second, ShiftOperatorRole.ADDITIONAL, manager);
        assertThat(current.getEndReading()).isEqualByComparingTo("2005.000");
        ArgumentCaptor<ShiftNozzleTurn> next = ArgumentCaptor.forClass(ShiftNozzleTurn.class);
        verify(turnRepository).save(next.capture());
        assertThat(next.getValue().getOperatorId()).isEqualTo(SECOND);
        assertThat(next.getValue().getStartReading()).isEqualByComparingTo("2005.000");
        assertThat(next.getValue().getStartReason()).isEqualTo(NozzleTurnStartReason.SWAP);
    }

    @Test
    void closingReadingBelowTheLastTurnsStartIsRefused() {
        when(turnRepository.findByShiftIdAndEndedAtIsNull(SHIFT_ID)).thenReturn(List.of(turn(SECOND, "2005.000", null)));
        List<ShiftFuelReading> readings = List.of(closingReading("2004.000"));

        assertThatThrownBy(() -> turnService.requireClosingReadingsAfterTurns(shift, readings,
                Map.of(NOZZLE_ID, new BigDecimal("99999999.999"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("CLOSING_READING_BELOW_TURN_START"));
    }

    @Test
    void closeEndsEveryOpenTurnAtTheClosingReading() {
        ShiftNozzleTurn open = turn(SECOND, "2005.000", null);
        when(turnRepository.findByShiftIdAndEndedAtIsNull(SHIFT_ID)).thenReturn(List.of(open));

        turnService.finishTurnsAtClose(shift, List.of(closingReading("2020.000")), NOW);

        assertThat(open.getEndReading()).isEqualByComparingTo("2020.000");
        assertThat(open.getEndedAt()).isEqualTo(NOW);
    }

    private ShiftNozzleTurn givenNozzleHeldBy(long operatorId, Long reliefId) {
        ShiftNozzleTurn current = turn(operatorId, "2000.000", reliefId);
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(shift);
        when(fuelReadingRepository.findByShiftId(SHIFT_ID)).thenReturn(List.of(closingReading(null)));
        when(nozzleRepository.findById(NOZZLE_ID)).thenReturn(Optional.of(Nozzle.builder().id(NOZZLE_ID).nozzleNumber(2)
                .fuelType(FuelType.PETROL).maxMeterValue(new BigDecimal("99999999.999")).build()));
        when(turnRepository.findByShiftIdAndEndedAtIsNull(SHIFT_ID)).thenReturn(List.of(current));
        return current;
    }

    private void assertSwapRefused(long toOperatorId, String reading, String code) {
        SwapNozzleRequest request = new SwapNozzleRequest(NOZZLE_ID, toOperatorId, new BigDecimal(reading), null);
        assertThatThrownBy(() -> turnService.swapNozzle(PUMP_ID, SHIFT_ID, request, manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(turnRepository, never()).save(any());
    }

    private static ShiftNozzleTurn turn(long operatorId, String start, Long reliefId) {
        return ShiftNozzleTurn.builder().id(1L).shiftId(SHIFT_ID).nozzleId(NOZZLE_ID).operatorId(operatorId)
                .startReading(new BigDecimal(start)).reliefId(reliefId).build();
    }

    private static ShiftFuelReading closingReading(String end) {
        return ShiftFuelReading.builder().shiftId(SHIFT_ID).nozzleId(NOZZLE_ID).fuelType(FuelType.PETROL)
                .startReading(new BigDecimal("2000.000")).endReading(end == null ? null : new BigDecimal(end))
                .priceSnapshot(new BigDecimal("100.00")).build();
    }
}
