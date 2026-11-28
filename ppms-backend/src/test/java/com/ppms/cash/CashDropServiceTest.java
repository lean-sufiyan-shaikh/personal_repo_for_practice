package com.ppms.cash;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashDropServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final long DROP_ID = 60L;
    private static final LocalDate SHIFT_DATE = LocalDate.of(2026, 11, 28);

    @Mock private CashDropRepository dropRepository;
    @Mock private CashEventRepository cashEventRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationDedupService dedupService;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @InjectMocks private CashDropService dropService;

    private final User operator = User.builder().id(7L).fullName("Ravi").role(UserRole.OPERATOR).build();
    private final User manager = User.builder().id(2L).fullName("Manager").role(UserRole.MANAGER).build();

    @Test
    void dropWaitsForConfirmationAndNotifiesTheManagers() {
        givenShift(ShiftStatus.OPEN);
        when(dropRepository.save(any(CashDrop.class))).thenAnswer(invocation -> {
            CashDrop drop = invocation.getArgument(0);
            drop.setId(DROP_ID);
            return drop;
        });

        CashDropResponse response = dropService.recordDrop(PUMP_ID, SHIFT_ID, new RecordCashDropRequest(new BigDecimal("600"),
                new CashDenominations(1, 0, 1, 0, 0, 0, BigDecimal.ZERO)), operator);

        assertThat(response.status()).isEqualTo(CashDropStatus.PENDING);
        assertThat(response.amount()).isEqualTo(new BigDecimal("600.00"));
        verify(dedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.CASH_DROP_PENDING), anyString(), anyString(),
                anyString(), anyMap());
    }

    @Test
    void breakdownThatDoesNotAddUpToTheDropIsRefused() {
        givenShift(ShiftStatus.OPEN);
        RecordCashDropRequest request = new RecordCashDropRequest(new BigDecimal("650.00"),
                new CashDenominations(1, 0, 1, 0, 0, 0, BigDecimal.ZERO));

        assertThatThrownBy(() -> dropService.recordDrop(PUMP_ID, SHIFT_ID, request, operator))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("DENOMINATIONS_TOTAL_MISMATCH");
                    assertThat(refused.getParams()).containsEntry("denominationTotal", "600.00");
                });
        verify(dropRepository, never()).save(any());
    }

    @Test
    void operatorCannotDropFromAnotherOperatorsShift() {
        givenShift(ShiftStatus.OPEN);
        User otherOperator = User.builder().id(9L).role(UserRole.OPERATOR).build();

        assertThatThrownBy(() -> dropService.recordDrop(PUMP_ID, SHIFT_ID, new RecordCashDropRequest(new BigDecimal("100"), null),
                otherOperator))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("CASH_DROP_NOT_OWN_SHIFT"));
    }

    @Test
    void closedShiftTakesNoMoreDrops() {
        givenShift(ShiftStatus.CLOSED_BALANCED);

        assertThatThrownBy(() -> dropService.recordDrop(PUMP_ID, SHIFT_ID, new RecordCashDropRequest(new BigDecimal("100"), null),
                operator))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("CASH_DROP_SHIFT_NOT_OPEN"));
    }

    @Test
    void otherOperatorCannotSeeTheDropsOfAShift() {
        when(shiftRepository.findById(SHIFT_ID)).thenReturn(Optional.of(shift(ShiftStatus.OPEN)));

        assertThatThrownBy(() -> dropService.dropsForShift(PUMP_ID, SHIFT_ID, User.builder().id(9L).role(UserRole.OPERATOR).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void personWhoRecordedADropCannotConfirmIt() {
        givenPendingDrop(manager.getId());

        assertThatThrownBy(() -> dropService.confirmDrop(PUMP_ID, DROP_ID, manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("CASH_DROP_SELF_CONFIRMATION"));
        verify(cashEventRepository, never()).save(any());
    }

    @Test
    void confirmingPutsTheDropInTheSafeOnItsShiftsDay() {
        CashDrop drop = givenPendingDrop(operator.getId());
        when(cashEventRepository.save(any(CashEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(businessClock.now()).thenReturn(OffsetDateTime.of(2026, 11, 28, 12, 0, 0, 0, ZoneOffset.UTC));

        dropService.confirmDrop(PUMP_ID, DROP_ID, manager);

        ArgumentCaptor<CashEvent> event = ArgumentCaptor.forClass(CashEvent.class);
        verify(cashEventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(CashEventType.CASH_DROP);
        assertThat(event.getValue().getAmount()).isEqualByComparingTo("600.00");
        assertThat(event.getValue().getEventDate()).isEqualTo(SHIFT_DATE);
        assertThat(drop.getStatus()).isEqualTo(CashDropStatus.CONFIRMED);
        assertThat(drop.getReviewedByUserId()).isEqualTo(manager.getId());
    }

    @Test
    void dropAlreadyDecidedCannotBeConfirmedAgain() {
        CashDrop drop = givenPendingDrop(operator.getId());
        drop.setStatus(CashDropStatus.CONFIRMED);

        assertThatThrownBy(() -> dropService.confirmDrop(PUMP_ID, DROP_ID, manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("CASH_DROP_NOT_PENDING"));
    }

    @Test
    void rejectedDropKeepsItsReasonAndNeverReachesTheSafe() {
        CashDrop drop = givenPendingDrop(operator.getId());

        dropService.rejectDrop(PUMP_ID, DROP_ID, new RejectCashDropRequest(" Bag held ₹500 only "), manager);

        assertThat(drop.getStatus()).isEqualTo(CashDropStatus.REJECTED);
        assertThat(drop.getRejectionReason()).isEqualTo("Bag held ₹500 only");
        verify(cashEventRepository, never()).save(any());
    }

    @Test
    void rejectionNeedsAReason() {
        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(new RejectCashDropRequest(" ")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("reason");
    }

    @Test
    void closeIsRefusedWhileADropWaitsForConfirmation() {
        when(dropRepository.totalForShift(SHIFT_ID, CashDropStatus.PENDING)).thenReturn(total(1, "600"));

        assertThatThrownBy(() -> dropService.confirmedTotalForClose(SHIFT_ID))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("CASH_DROPS_PENDING_AT_CLOSE");
                    assertThat(refused.getParams()).containsEntry("count", 1L).containsEntry("amount", "600.00");
                });
    }

    @Test
    void closeCountsOnlyTheConfirmedDrops() {
        when(dropRepository.totalForShift(SHIFT_ID, CashDropStatus.PENDING)).thenReturn(total(0, "0"));
        when(dropRepository.totalForShift(SHIFT_ID, CashDropStatus.CONFIRMED)).thenReturn(total(1, "600"));

        assertThat(dropService.confirmedTotalForClose(SHIFT_ID)).isEqualTo(new BigDecimal("600.00"));
    }

    private void givenShift(ShiftStatus status) {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(shift(status)));
    }

    private CashDrop givenPendingDrop(Long recordedBy) {
        CashDrop drop = CashDrop.builder().id(DROP_ID).pumpId(PUMP_ID).shiftId(SHIFT_ID).operatorId(operator.getId())
                .amount(new BigDecimal("600.00")).recordedByUserId(recordedBy).build();
        when(dropRepository.findShiftIdOfDrop(DROP_ID, PUMP_ID)).thenReturn(Optional.of(SHIFT_ID));
        when(shiftRepository.findByIdForUpdate(SHIFT_ID)).thenReturn(Optional.of(shift(ShiftStatus.OPEN)));
        when(dropRepository.findByIdAndPumpIdForUpdate(DROP_ID, PUMP_ID)).thenReturn(Optional.of(drop));
        return drop;
    }

    private Shift shift(ShiftStatus status) {
        return Shift.builder().id(SHIFT_ID).pumpId(PUMP_ID).operatorId(operator.getId()).status(status)
                .shiftDate(SHIFT_DATE).build();
    }

    private static CashDropRepository.DropTotal total(long count, String amount) {
        return new CashDropRepository.DropTotal() {
            @Override
            public long getDropCount() {
                return count;
            }

            @Override
            public BigDecimal getAmount() {
                return new BigDecimal(amount);
            }
        };
    }
}
