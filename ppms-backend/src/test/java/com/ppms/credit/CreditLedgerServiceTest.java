package com.ppms.credit;

import com.ppms.common.exception.BusinessException;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.expense.ApprovalPolicy;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.invoice.InvoicedSaleGuard;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.support.FixedBusinessClock;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditLedgerServiceTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 6);

    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final CreditPaymentRepository paymentRepository = mock(CreditPaymentRepository.class);
    private final CreditInterestChargeRepository interestChargeRepository = mock(CreditInterestChargeRepository.class);
    private final ShiftCreditEntryRepository creditEntryRepository = mock(ShiftCreditEntryRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final CreditInterestService interestService = mock(CreditInterestService.class);
    private final CreditAccountPolicyService accountPolicyService = mock(CreditAccountPolicyService.class);
    private final CreditPaymentReferenceCheck referenceCheck = mock(CreditPaymentReferenceCheck.class);

    private final CreditLedgerService ledgerService = new CreditLedgerService(clientRepository, paymentRepository,
            interestChargeRepository, creditEntryRepository, shiftRepository,
            mock(CreditEntryReassignmentRepository.class), mock(InvoicedSaleGuard.class), interestService,
            mock(ApprovalPolicy.class), accountPolicyService, mock(UserRepository.class),
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")), mock(ClosedDayGuard.class),
            mock(BalanceSheetStalenessService.class), referenceCheck);

    private final CreditClient client = CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Fleet Co").build();

    @BeforeEach
    void givenStandaloneClient() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(accountPolicyService.limitHolderId(client)).thenReturn(1L);
        when(paymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void paymentLargerThanWhatIsStillPayableIsRefused() {
        when(interestService.computeOutstandingWithChildren(1L)).thenReturn(new BigDecimal("300.00"));
        when(paymentRepository.sumPendingAmountByClientIds(List.of(1L))).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> ledgerService.recordPayment(PUMP_ID, 1L, payment("300.01", null), user(UserRole.MANAGER)))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_PAYMENT_EXCEEDS_PAYABLE");
                    assertThat(refusal.getParams()).containsEntry("payable", "300.00");
                });
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void postDatedChequeWaitsForApprovalEvenWhenAnOwnerRecordsIt() {
        when(interestService.computeOutstandingWithChildren(1L)).thenReturn(new BigDecimal("5000.00"));
        when(paymentRepository.sumPendingAmountByClientIds(List.of(1L))).thenReturn(BigDecimal.ZERO);

        CreditPayment saved = ledgerService.recordPayment(PUMP_ID, 1L, payment("2000.00", TODAY.plusDays(5)),
                user(UserRole.OWNER));

        assertThat(saved.getPaymentApprovalStatus()).isEqualTo(ExpenseApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    void chequeCannotBeApprovedBeforeItsDate() {
        User owner = user(UserRole.OWNER);
        givenPendingPayment(owner, TODAY.plusDays(5));

        assertThatThrownBy(() -> ledgerService.approvePayment(PUMP_ID, 9L, ExpenseApprovalStatus.APPROVED, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_CHEQUE_NOT_DUE");
    }

    @Test
    void ownerWhoRecordedAPostDatedChequeMayApproveItOnceItsDateHasCome() {
        User owner = user(UserRole.OWNER);
        CreditPayment cheque = givenPendingPayment(owner, TODAY);
        cheque.setPaidAt(TODAY.minusDays(5));

        CreditPayment approved = ledgerService.approvePayment(PUMP_ID, 9L, ExpenseApprovalStatus.APPROVED, owner);

        assertThat(approved.getPaymentApprovalStatus()).isEqualTo(ExpenseApprovalStatus.APPROVED);
    }

    @Test
    void ownerWhoRecordedAnOrdinaryPaymentCannotApproveIt() {
        User owner = user(UserRole.OWNER);
        givenPendingPayment(owner, null);

        assertThatThrownBy(() -> ledgerService.approvePayment(PUMP_ID, 9L, ExpenseApprovalStatus.APPROVED, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_SELF_APPROVAL");
    }

    @Test
    void interestWaiverNeedsAReasonAndCannotBeRepeated() {
        CreditInterestCharge waived = CreditInterestCharge.builder().id(4L).pumpId(PUMP_ID).clientId(1L)
                .amount(new BigDecimal("40.00")).voidedAt(OffsetDateTime.parse("2026-03-05T10:00:00+05:30")).build();
        when(interestChargeRepository.findById(4L)).thenReturn(Optional.of(waived));

        assertThatThrownBy(() -> ledgerService.waiveInterestCharge(PUMP_ID, 1L, 4L, " ",
                InterestWaiverKind.RECALCULATE, user(UserRole.OWNER)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INTEREST_WAIVER_REASON_REQUIRED");
        assertThatThrownBy(() -> ledgerService.waiveInterestCharge(PUMP_ID, 1L, 4L, "Again",
                InterestWaiverKind.RECALCULATE, user(UserRole.OWNER)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been waived");
    }

    @Test
    void creditEntryCannotBeMovedToAClientOutsideItsGroup() {
        CreditClient unrelated = CreditClient.builder().id(2L).pumpId(PUMP_ID).name("Other Co").build();
        when(clientRepository.findById(2L)).thenReturn(Optional.of(unrelated));
        when(creditEntryRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(
                ShiftCreditEntry.builder().id(20L).shiftId(30L).clientId(1L).amount(new BigDecimal("220.00")).build()));
        Shift shift = new Shift();
        shift.setPumpId(PUMP_ID);
        when(shiftRepository.findById(30L)).thenReturn(Optional.of(shift));
        ReassignCreditEntryRequest request = new ReassignCreditEntryRequest();
        request.setToClientId(2L);
        request.setReason("Wrong fleet");

        assertThatThrownBy(() -> ledgerService.reassignCreditEntry(PUMP_ID, 20L, request, user(UserRole.MANAGER)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_MOVE_OUTSIDE_GROUP");
        verify(creditEntryRepository, never()).save(any());
    }

    private CreditPayment givenPendingPayment(User recordedBy, LocalDate chequeDate) {
        CreditPayment payment = CreditPayment.builder().id(9L).pumpId(PUMP_ID).clientId(1L)
                .amount(new BigDecimal("2000.00")).paidAt(TODAY).chequeDate(chequeDate)
                .recordedById(recordedBy.getId()).paymentApprovalStatus(ExpenseApprovalStatus.PENDING_APPROVAL).build();
        when(paymentRepository.findClientIdByIdAndPumpId(9L, PUMP_ID)).thenReturn(Optional.of(1L));
        when(paymentRepository.findById(9L)).thenReturn(Optional.of(payment));
        return payment;
    }

    private static RecordPaymentRequest payment(String amount, LocalDate chequeDate) {
        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setAmount(new BigDecimal(amount));
        request.setPaymentMode(chequeDate != null ? PaymentMode.CHEQUE : PaymentMode.CASH);
        request.setPaidAt(TODAY);
        request.setChequeDate(chequeDate);
        return request;
    }

    private static User user(UserRole role) {
        User user = new User();
        user.setId(role.ordinal() + 100L);
        user.setRole(role);
        return user;
    }
}
