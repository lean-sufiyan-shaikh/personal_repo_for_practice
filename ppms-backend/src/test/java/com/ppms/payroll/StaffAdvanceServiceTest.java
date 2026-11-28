package com.ppms.payroll;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaffAdvanceServiceTest {

    private static final Long PUMP_ID = 6L;
    private static final Long STAFF_ID = 40L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    private final StaffAdvanceRepository advanceRepository = mock(StaffAdvanceRepository.class);
    private final StaffAdvanceRecoveryRepository recoveryRepository = mock(StaffAdvanceRecoveryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CashDrawerService cashDrawerService = mock(CashDrawerService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BusinessClock businessClock = mock(BusinessClock.class);

    private final StaffAdvanceService service = new StaffAdvanceService(advanceRepository, recoveryRepository, userRepository,
            cashDrawerService, mock(ClosedDayGuard.class), auditService, businessClock);

    private final User manager = User.builder().id(1L).role(UserRole.MANAGER).fullName("Asha").assignedPumpId(PUMP_ID).build();
    private final User accountant = User.builder().id(STAFF_ID).role(UserRole.ACCOUNTANT).fullName("Ravi")
            .assignedPumpId(PUMP_ID).build();

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(TODAY);
        when(businessClock.now()).thenReturn(OffsetDateTime.parse("2026-11-27T10:00:00+05:30"));
        when(userRepository.findByIdForUpdate(STAFF_ID)).thenReturn(Optional.of(accountant));
        when(userRepository.findAllById(any())).thenReturn(List.of(manager, accountant));
        when(advanceRepository.save(any(StaffAdvance.class))).thenAnswer(invocation -> {
            StaffAdvance advance = invocation.getArgument(0);
            if (advance.getId() == null) {
                advance.setId(20L);
            }
            return advance;
        });
        when(recoveryRepository.sumRecoveredOf(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void advanceIsTakenOutOfTheSafeOnItsDay() {
        when(cashDrawerService.recordAutomaticCashOut(eq(PUMP_ID), any(), eq(TODAY), anyString(), eq(manager)))
                .thenReturn(CashEvent.builder().id(8L).build());

        StaffAdvanceResponses.AdvanceResponse given = service.giveAdvance(PUMP_ID,
                new GiveStaffAdvanceRequest(STAFF_ID, new BigDecimal("1500"), TODAY, "  "), manager);

        assertThat(given.outstanding()).isEqualByComparingTo("1500.00");
        assertThat(given.note()).isNull();
        verify(cashDrawerService).recordAutomaticCashOut(eq(PUMP_ID), eq(new BigDecimal("1500.00")), eq(TODAY), anyString(), eq(manager));
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.STAFF_ADVANCE_GIVEN), anyString(), anyString(), anyString(), eq(manager));
    }

    @Test
    void advanceCannotBeDatedAheadOrGivenToStaffOfAnotherPumpOrAnOwner() {
        assertThatThrownBy(() -> service.giveAdvance(PUMP_ID,
                new GiveStaffAdvanceRequest(STAFF_ID, new BigDecimal("100"), TODAY.plusDays(1), null), manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.STAFF_ADVANCE_DATE_IN_FUTURE));

        accountant.setAssignedPumpId(99L);
        assertThatThrownBy(() -> service.giveAdvance(PUMP_ID,
                new GiveStaffAdvanceRequest(STAFF_ID, new BigDecimal("10"), TODAY, null), manager))
                .isInstanceOf(ResourceNotFoundException.class);

        accountant.setAssignedPumpId(PUMP_ID);
        accountant.setRole(UserRole.OWNER);
        assertThatThrownBy(() -> service.giveAdvance(PUMP_ID,
                new GiveStaffAdvanceRequest(STAFF_ID, new BigDecimal("10"), TODAY, null), manager))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(cashDrawerService, never()).recordAutomaticCashOut(any(), any(), any(), anyString(), any());
    }

    @Test
    void payrollRecoversTheOldestAdvanceFirstAndNeverMoreThanThePayLeft() {
        StaffAdvance older = advance(1L, "1000.00");
        StaffAdvance newer = advance(2L, "500.00");
        when(advanceRepository.findByPumpIdAndStaffUserIdAndStatusOrderByAdvanceDateAscIdAsc(PUMP_ID, STAFF_ID,
                StaffAdvanceStatus.ACTIVE)).thenReturn(List.of(older, newer));
        when(recoveryRepository.findByAdvanceIdIn(List.of(1L, 2L))).thenReturn(List.of(
                StaffAdvanceRecovery.builder().advanceId(1L).amount(new BigDecimal("600.00")).build()));

        BigDecimal recovered = service.recoverForPayroll(payroll(), new BigDecimal("800.00"));

        assertThat(recovered).isEqualByComparingTo("800.00");
        ArgumentCaptor<List<StaffAdvanceRecovery>> saved = ArgumentCaptor.forClass(List.class);
        verify(recoveryRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(StaffAdvanceRecovery::getAdvanceId).containsExactly(1L, 2L);
        assertThat(saved.getValue()).extracting(recovery -> recovery.getAmount().toPlainString())
                .containsExactly("400.00", "400.00");
    }

    @Test
    void payrollRecoversOnlyWhatIsOutstandingAndNothingWhenNoPayIsLeft() {
        when(advanceRepository.findByPumpIdAndStaffUserIdAndStatusOrderByAdvanceDateAscIdAsc(PUMP_ID, STAFF_ID,
                StaffAdvanceStatus.ACTIVE)).thenReturn(List.of(advance(1L, "700.00")));

        assertThat(service.recoverForPayroll(payroll(), new BigDecimal("800.00"))).isEqualByComparingTo("700.00");
        assertThat(service.recoverForPayroll(payroll(), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void voidedAdvanceGoesBackIntoTheSafeOnceAndOwesNothing() {
        StaffAdvance advance = advance(20L, "1000.00");
        advance.setCashEventId(8L);
        when(advanceRepository.findByIdAndPumpId(20L, PUMP_ID)).thenReturn(Optional.of(advance));

        StaffAdvanceResponses.AdvanceResponse voided = service.voidAdvance(PUMP_ID, 20L, " Given by mistake ", manager);

        assertThat(voided.status()).isEqualTo("VOIDED");
        assertThat(voided.outstanding()).isEqualByComparingTo("0.00");
        assertThat(advance.getVoidReason()).isEqualTo("Given by mistake");
        verify(cashDrawerService).reverseAutomaticEvent(eq(PUMP_ID), eq(8L), anyString(), eq(manager));
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.STAFF_ADVANCE_VOIDED), anyString(), anyString(), anyString(), eq(manager));
        assertThatThrownBy(() -> service.voidAdvance(PUMP_ID, 20L, "Again", manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.STAFF_ADVANCE_ALREADY_VOIDED));
    }

    @Test
    void advancePayrollHasTakenFromCannotBeVoided() {
        when(advanceRepository.findByIdAndPumpId(20L, PUMP_ID)).thenReturn(Optional.of(advance(20L, "1500.00")));
        when(recoveryRepository.sumRecoveredOf(20L)).thenReturn(new BigDecimal("800"));

        assertThatThrownBy(() -> service.voidAdvance(PUMP_ID, 20L, "Typed twice", manager))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.STAFF_ADVANCE_ALREADY_RECOVERED);
                    assertThat(error.getParams()).containsEntry("recovered", "800.00");
                });
    }

    @Test
    void listTotalsWhatEachStaffMemberStillOwesLargestFirst() {
        StaffAdvance operatorAdvance = advance(3L, "100.00");
        operatorAdvance.setStaffUserId(manager.getId());
        StaffAdvance voided = advance(4L, "900.00");
        voided.setStatus(StaffAdvanceStatus.VOIDED);
        when(advanceRepository.findByPumpIdOrderByAdvanceDateDescIdDesc(PUMP_ID))
                .thenReturn(List.of(advance(1L, "300.00"), advance(2L, "200.00"), operatorAdvance, voided));
        when(recoveryRepository.findByAdvanceIdIn(anyList())).thenReturn(List.of());

        StaffAdvanceResponses.AdvanceListResponse list = service.listAdvances(PUMP_ID, null);

        assertThat(list.advances()).hasSize(4);
        assertThat(list.outstandingByStaff()).extracting(StaffAdvanceResponses.StaffOutstanding::staffUserId)
                .containsExactly(STAFF_ID, manager.getId());
        assertThat(list.outstandingByStaff()).extracting(line -> line.outstanding().toPlainString())
                .containsExactly("500.00", "100.00");
    }

    private static StaffAdvance advance(Long id, String amount) {
        return StaffAdvance.builder().id(id).pumpId(PUMP_ID).staffUserId(STAFF_ID).amount(new BigDecimal(amount))
                .advanceDate(TODAY.minusDays(5)).givenByUserId(1L).build();
    }

    private static PayrollRecord payroll() {
        return PayrollRecord.builder().id(90L).pumpId(PUMP_ID).userId(STAFF_ID).build();
    }
}
