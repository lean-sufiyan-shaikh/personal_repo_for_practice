package com.ppms.expense;

import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pettycash.PettyCashLedger;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long EXPENSE_ID = 40L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private PumpExpenseRepository expenseRepository;
    @Mock private ApprovalPolicy approvalPolicy;
    @Mock private CashDrawerService cashDrawerService;
    @Mock private PettyCashLedger pettyCashLedger;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private BalanceSheetStalenessService balanceSheetStalenessService;

    private ExpenseService expenseService;
    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();

    @BeforeEach
    void createService() {
        expenseService = new ExpenseService(expenseRepository, approvalPolicy,
                new ExpenseCashMovements(cashDrawerService, pettyCashLedger), pumpLocationRepository, auditService,
                businessClock, closedDayGuard, balanceSheetStalenessService);
        lenient().when(businessClock.today()).thenReturn(TODAY);
        lenient().when(expenseRepository.save(any(PumpExpense.class))).thenAnswer(invocation -> {
            PumpExpense expense = invocation.getArgument(0);
            if (expense.getId() == null) {
                expense.setId(EXPENSE_ID);
            }
            return expense;
        });
    }

    @Test
    void expenseDatedInTheFutureIsRefused() {
        assertThatThrownBy(() -> expenseService.create(PUMP_ID, request(ExpensePaidFrom.SAFE, TODAY.plusDays(1)), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("EXPENSE_DATE_IN_FUTURE"));
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void expenseNeedsToSayWhereItWasPaidFrom() {
        CreateExpenseRequest request = request(null, TODAY);

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("paidFrom");
    }

    @Test
    void approvedExpensePaidFromTheSafeTakesItsCashOut() {
        givenStatus(ExpenseApprovalStatus.APPROVED);
        when(cashDrawerService.recordAutomaticCashOut(eq(PUMP_ID), eq(new BigDecimal("400.00")), eq(TODAY), anyString(), eq(owner)))
                .thenReturn(CashEvent.builder().id(70L).build());

        PumpExpense expense = expenseService.create(PUMP_ID, request(ExpensePaidFrom.SAFE, TODAY), owner);

        assertThat(expense.getCashEventId()).isEqualTo(70L);
    }

    @ParameterizedTest
    @EnumSource(value = ExpensePaidFrom.class, names = {"BANK", "OWNER_POCKET"})
    void bankAndOwnersPocketExpensesMoveNoCash(ExpensePaidFrom paidFrom) {
        givenStatus(ExpenseApprovalStatus.APPROVED);

        PumpExpense expense = expenseService.create(PUMP_ID, request(paidFrom, TODAY), owner);

        assertThat(expense.getCashEventId()).isNull();
        verifyNoInteractions(cashDrawerService, pettyCashLedger);
    }

    @Test
    void pettyCashExpenseWaitingForApprovalDoesNotDrawTheBox() {
        givenStatus(ExpenseApprovalStatus.PENDING_APPROVAL);

        expenseService.create(PUMP_ID, request(ExpensePaidFrom.PETTY_CASH, TODAY), owner);

        verifyNoInteractions(pettyCashLedger);
    }

    @Test
    void onlyAnApprovedExpenseCanBeVoided() {
        givenExisting(ExpenseApprovalStatus.VOIDED, ExpensePaidFrom.SAFE, 70L);

        assertThatThrownBy(() -> expenseService.voidExpense(PUMP_ID, EXPENSE_ID, "Entered twice", owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("EXPENSE_NOT_VOIDABLE"));
        verifyNoInteractions(cashDrawerService);
    }

    @Test
    void voidingASafeExpensePutsItsCashBackOnAnOpenDay() {
        LocalDate yesterday = TODAY.minusDays(1);
        PumpExpense expense = givenExisting(ExpenseApprovalStatus.APPROVED, ExpensePaidFrom.SAFE, 70L);
        expense.setExpenseDate(yesterday);

        expenseService.voidExpense(PUMP_ID, EXPENSE_ID, " Entered twice ", owner);

        verify(closedDayGuard).requireOpen(PUMP_ID, yesterday);
        verify(cashDrawerService).reverseAutomaticEvent(PUMP_ID, 70L, "expense #40 voided: Entered twice", owner);
        assertThat(expense.getApprovalStatus()).isEqualTo(ExpenseApprovalStatus.VOIDED);
        assertThat(expense.getVoidReason()).isEqualTo("Entered twice");
    }

    @Test
    void voidingAPettyCashExpensePutsTheMoneyBackInTheBox() {
        givenExisting(ExpenseApprovalStatus.APPROVED, ExpensePaidFrom.PETTY_CASH, null);

        expenseService.voidExpense(PUMP_ID, EXPENSE_ID, "Wrong box", owner);

        verify(pettyCashLedger).reverseExpenseDraw(PUMP_ID, EXPENSE_ID, "Wrong box", owner);
    }

    private void givenStatus(ExpenseApprovalStatus status) {
        when(approvalPolicy.resolveExpenseStatus(eq(PUMP_ID), eq(owner), any(), any(), eq(TODAY))).thenReturn(status);
    }

    private PumpExpense givenExisting(ExpenseApprovalStatus status, ExpensePaidFrom paidFrom, Long cashEventId) {
        PumpExpense expense = PumpExpense.builder().id(EXPENSE_ID).pumpId(PUMP_ID).category(ExpenseCategory.MAINTENANCE)
                .amount(new BigDecimal("400.00")).expenseDate(TODAY).paidFrom(paidFrom).approvalStatus(status)
                .cashEventId(cashEventId).build();
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
        when(expenseRepository.findById(EXPENSE_ID)).thenReturn(Optional.of(expense));
        return expense;
    }

    private static CreateExpenseRequest request(ExpensePaidFrom paidFrom, LocalDate date) {
        CreateExpenseRequest request = new CreateExpenseRequest();
        request.setCategory(ExpenseCategory.MAINTENANCE);
        request.setAmount(new BigDecimal("400"));
        request.setDescription("Repair");
        request.setExpenseDate(date);
        request.setPaidFrom(paidFrom);
        return request;
    }
}
