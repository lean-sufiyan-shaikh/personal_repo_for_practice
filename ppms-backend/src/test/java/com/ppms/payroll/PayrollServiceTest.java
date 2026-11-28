package com.ppms.payroll;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.planning.StaffLeaveRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.shift.DiscrepancyResolution;
import com.ppms.shift.DiscrepancyType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftOperator;
import com.ppms.shift.ShiftOperatorRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayrollServiceTest {

    private static final Long PUMP_ID = 6L;
    private static final Long STAFF_ID = 40L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final OffsetDateTime NINE_AM = OffsetDateTime.parse("2026-11-20T09:00:00+05:30");

    private final PayrollRepository payrollRepository = mock(PayrollRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final StaffLeaveRepository staffLeaveRepository = mock(StaffLeaveRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BusinessClock businessClock = mock(BusinessClock.class);
    private final ClosedDayGuard closedDayGuard = mock(ClosedDayGuard.class);
    private final CashDrawerService cashDrawerService = mock(CashDrawerService.class);
    private final StaffAdvanceService staffAdvanceService = mock(StaffAdvanceService.class);
    private final ShiftOperatorRepository shiftOperatorRepository = mock(ShiftOperatorRepository.class);

    private final PayrollResolvedShiftRepository resolvedShiftRepository = mock(PayrollResolvedShiftRepository.class);

    private final PayrollService service = new PayrollService(payrollRepository, resolvedShiftRepository,
            userRepository, shiftRepository, staffLeaveRepository, auditService, businessClock, closedDayGuard,
            cashDrawerService, mock(BalanceSheetStalenessService.class), staffAdvanceService, shiftOperatorRepository);

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).fullName("Owner").build();
    private final User operator = User.builder().id(STAFF_ID).role(UserRole.OPERATOR).fullName("Ravi").assignedPumpId(PUMP_ID)
            .shift1HourlyRate(new BigDecimal("100.00")).standardHourlyRate(new BigDecimal("80.00")).build();

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(TODAY);
        when(businessClock.now()).thenReturn(NINE_AM);
        when(userRepository.findByIdForUpdate(STAFF_ID)).thenReturn(Optional.of(operator));
        when(payrollRepository.save(any(PayrollRecord.class))).thenAnswer(invocation -> {
            PayrollRecord record = invocation.getArgument(0);
            if (record.getId() == null) {
                record.setId(90L);
            }
            return record;
        });
        when(staffAdvanceService.recoverForPayroll(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void operatorIsPaidTheNightRateForNightShiftsAndTheStandardRateForTheRestIncludingWorkOnOtherShifts() {
        when(shiftRepository.findByPumpIdAndOperatorIdAndDateRange(PUMP_ID, STAFF_ID, TODAY.minusDays(7), TODAY))
                .thenReturn(List.of(shift(1L, 8 * 60, true), shift(2L, 8 * 60, false), shift(3L, 0, false)));
        Shift colleaguesShift = shift(9L, 0, false);
        colleaguesShift.setActualEndTime(null);
        when(shiftOperatorRepository.findFinishedWorkOnOtherShifts(PUMP_ID, STAFF_ID, TODAY.minusDays(7), TODAY))
                .thenReturn(List.of(ShiftOperator.builder().shiftId(9L).joinedAt(NINE_AM).leftAt(NINE_AM.plusMinutes(90)).build()));
        when(shiftRepository.findAllById(any())).thenReturn(List.of(colleaguesShift));

        PayrollRecord record = service.generate(PUMP_ID, request(TODAY.minusDays(7), TODAY), owner);

        assertThat(record.getSalaryType()).isEqualTo("HOURLY_SHIFT");
        assertThat(record.getShift1Shifts()).isEqualTo(1);
        assertThat(record.getShift1Hours()).isEqualByComparingTo("8.00");
        assertThat(record.getStandardShifts()).isEqualTo(3);
        assertThat(record.getStandardHours()).isEqualByComparingTo("9.50");
        assertThat(record.getGrossAmount()).isEqualByComparingTo("1560.00");
        assertThat(record.getNetPay()).isEqualByComparingTo("1560.00");
    }

    @Test
    void managerIsPaidTheDailyRateForEveryDayNotOnLeave() {
        User manager = User.builder().id(STAFF_ID).role(UserRole.MANAGER).fullName("Asha").assignedPumpId(PUMP_ID)
                .dailyRate(new BigDecimal("900.00")).build();
        when(userRepository.findByIdForUpdate(STAFF_ID)).thenReturn(Optional.of(manager));
        when(staffLeaveRepository.countByUserIdAndLeaveDateBetween(STAFF_ID, TODAY.minusDays(9), TODAY)).thenReturn(2L);

        PayrollRecord record = service.generate(PUMP_ID, request(TODAY.minusDays(9), TODAY), owner);

        assertThat(record.getSalaryType()).isEqualTo("DAILY");
        assertThat(record.getTotalDays()).isEqualTo(10);
        assertThat(record.getDaysWorked()).isEqualTo(8);
        assertThat(record.getGrossAmount()).isEqualByComparingTo("7200.00");
    }

    @Test
    void staffWithoutRatesCannotBePaid() {
        operator.setStandardHourlyRate(null);

        assertThatThrownBy(() -> service.generate(PUMP_ID, request(TODAY.minusDays(1), TODAY), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_HOURLY_RATES_MISSING));

        User manager = User.builder().id(STAFF_ID).role(UserRole.MANAGER).fullName("Asha").assignedPumpId(PUMP_ID).build();
        when(userRepository.findByIdForUpdate(STAFF_ID)).thenReturn(Optional.of(manager));
        assertThatThrownBy(() -> service.generate(PUMP_ID, request(TODAY.minusDays(1), TODAY), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_DAILY_RATE_MISSING));
    }

    @Test
    void periodEndingBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> service.generate(PUMP_ID, request(TODAY, TODAY.minusDays(1)), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_PERIOD_END_BEFORE_START));
    }

    @Test
    void deductionsAboveThePayLeaveNothingToPayAndCarryTheRestForward() {
        when(shiftRepository.findByPumpIdAndOperatorIdAndDateRange(PUMP_ID, STAFF_ID, TODAY.minusDays(1), TODAY))
                .thenReturn(List.of(shift(1L, 8 * 60, false)));
        Shift shortShift = shift(2L, 0, false);
        shortShift.setDiscrepancyType(DiscrepancyType.SHORT);
        shortShift.setDiscrepancyAmount(new BigDecimal("1500.00"));
        shortShift.setCashRecoveryAmount(new BigDecimal("160.00"));
        when(shiftRepository.findUntakenSalaryDeductionShifts(PUMP_ID, STAFF_ID)).thenReturn(List.of(shortShift));

        PayrollRecord record = service.generate(PUMP_ID, request(TODAY.minusDays(1), TODAY), owner);

        assertThat(record.getGrossAmount()).isEqualByComparingTo("640.00");
        assertThat(record.getDeductions()).isEqualByComparingTo("1340.00");
        assertThat(record.getNetPay()).isEqualByComparingTo("0.00");
        assertThat(record.getUncoveredDeductions()).isEqualByComparingTo("700.00");
        verify(staffAdvanceService).recoverForPayroll(eq(record), argThat(available -> available.signum() == 0));
    }

    @Test
    void deductionsCarriedFromEarlierPayrollAreTakenAndTheEarlierPayrollPointsAtThisOne() {
        when(shiftRepository.findByPumpIdAndOperatorIdAndDateRange(PUMP_ID, STAFF_ID, TODAY.minusDays(1), TODAY))
                .thenReturn(List.of(shift(1L, 8 * 60, false), shift(2L, 8 * 60, false)));
        PayrollRecord earlier = PayrollRecord.builder().id(80L).pumpId(PUMP_ID).userId(STAFF_ID)
                .uncoveredDeductions(new BigDecimal("700.00")).build();
        when(payrollRepository.findWithUncarriedDeductionsBefore(PUMP_ID, STAFF_ID, TODAY.minusDays(1)))
                .thenReturn(List.of(earlier));

        PayrollRecord record = service.generate(PUMP_ID, request(TODAY.minusDays(1), TODAY), owner);

        assertThat(record.getOpeningDeductions()).isEqualByComparingTo("700.00");
        assertThat(record.getDeductions()).isEqualByComparingTo("700.00");
        assertThat(record.getNetPay()).isEqualByComparingTo("580.00");
        assertThat(record.getUncoveredDeductions()).isEqualByComparingTo("0.00");
        assertThat(earlier.getCarriedToPayrollId()).isEqualTo(record.getId());
    }

    @Test
    void outstandingAdvancesComeOffWhatIsLeftOfThePay() {
        when(shiftRepository.findByPumpIdAndOperatorIdAndDateRange(PUMP_ID, STAFF_ID, TODAY.minusDays(1), TODAY))
                .thenReturn(List.of(shift(1L, 10 * 60, false)));
        when(staffAdvanceService.recoverForPayroll(any(), eq(new BigDecimal("800.00")))).thenReturn(new BigDecimal("700.00"));

        PayrollRecord record = service.generate(PUMP_ID, request(TODAY.minusDays(1), TODAY), owner);

        assertThat(record.getAdvanceRecovered()).isEqualByComparingTo("700.00");
        assertThat(record.getNetPay()).isEqualByComparingTo("100.00");
    }

    @Test
    void draftWhoseDeductionsWereCarriedForwardCannotBeDeleted() {
        PayrollRecord record = PayrollRecord.builder().id(80L).pumpId(PUMP_ID).userId(STAFF_ID)
                .status(PayrollStatus.DRAFT).carriedToPayrollId(91L).build();
        when(payrollRepository.findById(80L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.deleteDraft(PUMP_ID, 80L, owner))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_DEDUCTIONS_CARRIED_FORWARD);
                    assertThat(error.getMessage()).contains("carried into payroll #91");
                });
        verify(payrollRepository, never()).delete(any());
    }

    @Test
    void approvedPayrollReturnsToDraftAndIsAuditedButADraftCannotBePaid() {
        PayrollRecord record = approvedRecord("640.00");

        service.changeStatus(PUMP_ID, 80L, new UpdatePayrollStatusRequest("draft", null, null, null), owner);

        assertThat(record.getStatus()).isEqualTo(PayrollStatus.DRAFT);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.PAYROLL_RETURNED_TO_DRAFT), anyString(), eq("80"), anyString(), eq(owner));
        assertThatThrownBy(() -> service.changeStatus(PUMP_ID, 80L, paid(TODAY, PayrollPaymentMode.BANK), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_STATUS_TRANSITION_INVALID));
    }

    @Test
    void payingNeedsTheDateAndModeAndADateNotInTheFuture() {
        approvedRecord("640.00");

        assertThatThrownBy(() -> service.changeStatus(PUMP_ID, 80L, new UpdatePayrollStatusRequest("PAID", null, null, null), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_PAYMENT_DETAILS_REQUIRED));
        assertThatThrownBy(() -> service.changeStatus(PUMP_ID, 80L, paid(TODAY.plusDays(1), PayrollPaymentMode.CASH), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_PAID_DATE_IN_FUTURE));
        assertThatThrownBy(() -> service.changeStatus(PUMP_ID, 80L, new UpdatePayrollStatusRequest(" ", null, null, null), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_STATUS_REQUIRED));
    }

    @Test
    void payingInCashTakesTheNetPayFromTheSafeOnThePaidDate() {
        PayrollRecord record = approvedRecord("640.00");
        when(cashDrawerService.recordAutomaticCashOut(eq(PUMP_ID), any(), eq(TODAY), anyString(), eq(owner)))
                .thenReturn(CashEvent.builder().id(15L).build());

        service.changeStatus(PUMP_ID, 80L, paid(TODAY, PayrollPaymentMode.CASH), owner);

        assertThat(record.getStatus()).isEqualTo(PayrollStatus.PAID);
        assertThat(record.getPaidDate()).isEqualTo(TODAY);
        assertThat(record.getPaymentReference()).isEqualTo("Voucher 12");
        assertThat(record.getPaidBy()).isEqualTo(owner.getId());
        assertThat(record.getCashEventId()).isEqualTo(15L);
        verify(closedDayGuard).requireOpen(PUMP_ID, TODAY);
        verify(cashDrawerService).recordAutomaticCashOut(eq(PUMP_ID), eq(new BigDecimal("640.00")), eq(TODAY), anyString(), eq(owner));
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.PAYROLL_PAID), anyString(), eq("80"), anyString(), eq(owner));
    }

    @Test
    void payingByBankTakesNothingFromTheSafe() {
        PayrollRecord record = approvedRecord("640.00");

        service.changeStatus(PUMP_ID, 80L, paid(TODAY, PayrollPaymentMode.BANK), owner);

        assertThat(record.getCashEventId()).isNull();
        verify(closedDayGuard).requireOpen(PUMP_ID, TODAY);
        verify(cashDrawerService, never()).recordAutomaticCashOut(any(), any(), any(), anyString(), any());
    }

    @Test
    void workedMinutesArePaidAtTheHourlyRateRoundedOnce() {
        when(shiftRepository.findByPumpIdAndOperatorIdAndDateRange(PUMP_ID, STAFF_ID, TODAY.minusDays(1), TODAY))
                .thenReturn(List.of(shift(1L, 100, false)));

        PayrollRecord record = service.generate(PUMP_ID, request(TODAY.minusDays(1), TODAY), owner);

        assertThat(record.getGrossAmount()).isEqualByComparingTo("133.33");
    }

    @Test
    void periodOverlappingAnEarlierPayrollIsRefusedBeforeAnyDiscrepancyIsResolved() {
        GeneratePayrollRequest request = request(TODAY.minusDays(2), TODAY.plusDays(5));
        request.setDeductFromSalaryShiftIds(List.of(2L));
        when(payrollRepository.findFirstByPumpIdAndUserIdAndPeriodFromLessThanEqualAndPeriodToGreaterThanEqual(
                PUMP_ID, STAFF_ID, TODAY.plusDays(5), TODAY.minusDays(2)))
                .thenReturn(Optional.of(PayrollRecord.builder().periodFrom(TODAY.minusDays(7)).periodTo(TODAY).build()));

        assertThatThrownBy(() -> service.generate(PUMP_ID, request, owner))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_PERIOD_OVERLAPS);
                    assertThat(error.getMessage()).contains("already covers", "overlaps");
                });
        verify(shiftRepository, never()).findByIdForUpdate(any());
        verify(payrollRepository, never()).save(any());
    }

    @Test
    void overDiscrepancyCannotBeDeductedFromSalary() {
        Shift over = shift(2L, 0, false);
        over.setStatus(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        over.setDiscrepancyType(DiscrepancyType.OVER);
        when(shiftRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(over));
        GeneratePayrollRequest request = request(TODAY.minusDays(1), TODAY);
        request.setDeductFromSalaryShiftIds(List.of(2L));

        assertThatThrownBy(() -> service.generate(PUMP_ID, request, owner))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.PAYROLL_SHIFT_DISCREPANCY_IS_OVER);
                    assertThat(error.getMessage()).contains("only SHORT discrepancies");
                });
        assertThat(over.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(over.getDiscrepancyResolution()).isNull();
        verify(payrollRepository, never()).save(any());
    }

    @Test
    void chosenShortageIsResolvedAsASalaryDeductionAndDeletingTheDraftRestoresIt() {
        Shift shortShift = shift(2L, 0, false);
        shortShift.setStatus(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        shortShift.setDiscrepancyType(DiscrepancyType.SHORT);
        shortShift.setDiscrepancyAmount(new BigDecimal("50.00"));
        when(shiftRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(shortShift));
        GeneratePayrollRequest request = request(TODAY.minusDays(1), TODAY);
        request.setDeductFromSalaryShiftIds(List.of(2L));

        service.generate(PUMP_ID, request, owner);

        assertThat(shortShift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        assertThat(shortShift.getDiscrepancyResolution()).isEqualTo(DiscrepancyResolution.SALARY_DEDUCTION);

        PayrollRecord draft = PayrollRecord.builder().id(90L).pumpId(PUMP_ID).userId(STAFF_ID).status(PayrollStatus.DRAFT).build();
        when(payrollRepository.findById(90L)).thenReturn(Optional.of(draft));
        when(resolvedShiftRepository.findByPayrollId(90L)).thenReturn(List.of(PayrollResolvedShift.builder()
                .payrollId(90L).shiftId(2L).previousStatus(ShiftStatus.CLOSED_DISCREPANCY_PENDING).build()));

        service.deleteDraft(PUMP_ID, 90L, owner);

        assertThat(shortShift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(shortShift.getDiscrepancyResolution()).isNull();
        verify(payrollRepository).delete(draft);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.PAYROLL_DELETED), anyString(), eq("90"), anyString(), eq(owner));
    }

    private PayrollRecord approvedRecord(String netPay) {
        PayrollRecord record = PayrollRecord.builder().id(80L).pumpId(PUMP_ID).userId(STAFF_ID).status(PayrollStatus.APPROVED)
                .grossAmount(new BigDecimal(netPay)).netPay(new BigDecimal(netPay))
                .periodFrom(TODAY.minusDays(6)).periodTo(TODAY).build();
        when(payrollRepository.findById(80L)).thenReturn(Optional.of(record));
        return record;
    }

    private static UpdatePayrollStatusRequest paid(LocalDate date, PayrollPaymentMode mode) {
        return new UpdatePayrollStatusRequest("PAID", date, mode, " Voucher 12 ");
    }

    private static GeneratePayrollRequest request(LocalDate from, LocalDate to) {
        GeneratePayrollRequest request = new GeneratePayrollRequest();
        request.setUserId(STAFF_ID);
        request.setPeriodFrom(from);
        request.setPeriodTo(to);
        return request;
    }

    private static Shift shift(Long id, int minutes, boolean night) {
        return Shift.builder().id(id).pumpId(PUMP_ID).operatorId(STAFF_ID).isNightShift(night)
                .actualStartTime(NINE_AM).actualEndTime(NINE_AM.plusMinutes(minutes)).build();
    }
}
