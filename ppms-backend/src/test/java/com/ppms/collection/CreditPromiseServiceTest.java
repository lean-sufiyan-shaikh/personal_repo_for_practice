package com.ppms.collection;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.credit.CreditAccountPolicyService;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditInterestService;
import com.ppms.credit.CreditPayment;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.support.FixedBusinessClock;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditPromiseServiceTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 6);

    private final CreditPaymentPromiseRepository promiseRepository = mock(CreditPaymentPromiseRepository.class);
    private final CreditClientAccessService clientAccessService = mock(CreditClientAccessService.class);
    private final CreditAccountPolicyService accountPolicyService = mock(CreditAccountPolicyService.class);
    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final CreditPaymentRepository paymentRepository = mock(CreditPaymentRepository.class);
    private final CreditInterestService interestService = mock(CreditInterestService.class);
    private final NotificationDedupService notificationDedupService = mock(NotificationDedupService.class);

    private final CreditPromiseService promiseService = new CreditPromiseService(promiseRepository,
            mock(CreditPaymentPromiseChangeRepository.class), clientAccessService, accountPolicyService,
            clientRepository, paymentRepository, interestService, mock(UserRepository.class), notificationDedupService,
            mock(AuditService.class), new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")));

    private final CreditClient parent = CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Fleet Co").build();
    private final CreditClient child = CreditClient.builder().id(2L).pumpId(PUMP_ID).name("Driver").parentClientId(1L)
            .build();

    @BeforeEach
    void givenSubAccountOfAGroupOwingThreeHundred() {
        when(clientAccessService.requireClientForPump(PUMP_ID, 2L)).thenReturn(child);
        when(accountPolicyService.lockLimitHolder(child)).thenReturn(parent);
        when(interestService.computeOutstandingWithChildren(1L)).thenReturn(new BigDecimal("300.00"));
        when(clientRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(clientRepository.findAllById(any())).thenReturn(List.of(parent));
        when(promiseRepository.save(any())).thenAnswer(call -> {
            CreditPaymentPromise promise = call.getArgument(0);
            if (promise.getId() == null) {
                promise.setId(50L);
            }
            return promise;
        });
    }

    @Test
    void promisedDateBeforeTodayIsRefused() {
        assertThatThrownBy(() -> record("100.00", TODAY.minusDays(1)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PROMISE_DATE_IN_PAST");
    }

    @Test
    void promisedDateMoreThanNinetyDaysAheadIsRefused() {
        assertThatThrownBy(() -> record("100.00", TODAY.plusDays(91)))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_PROMISE_DATE_TOO_FAR");
                    assertThat(refusal.getParams()).containsEntry("maxDays", 90);
                });
    }

    @Test
    void promiseOfMoreThanTheGroupOwesIsRefused() {
        assertThatThrownBy(() -> record("300.01", TODAY.plusDays(5)))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_PROMISE_EXCEEDS_OUTSTANDING");
                    assertThat(refusal.getParams()).containsEntry("outstanding", "300.00");
                });
    }

    @Test
    void subAccountsPromiseOfAllTheGroupOwesNinetyDaysAheadIsRecordedOnTheParent() {
        PromiseResponse promise = record("300.00", TODAY.plusDays(90));

        assertThat(promise.clientId()).isEqualTo(1L);
        assertThat(promise.amount()).isEqualByComparingTo("300.00");
    }

    @Test
    void secondOpenPromiseOfAnAccountIsRefused() {
        when(promiseRepository.findByClientIdAndStatus(1L, PromiseStatus.OPEN))
                .thenReturn(Optional.of(promise("200.00", TODAY, TODAY.plusDays(3))));

        assertThatThrownBy(() -> record("100.00", TODAY.plusDays(5)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PROMISE_ALREADY_OPEN");
    }

    @Test
    void editedAmountMayNotExceedWhatTheAccountOwes() {
        givenOpenPromise(promise("200.00", TODAY, TODAY.plusDays(3)));
        givenPayments();

        assertThatThrownBy(() -> promiseService.editPromise(PUMP_ID, 50L,
                new EditPromiseRequest(new BigDecimal("400.00"), TODAY.plusDays(10), null, null), user()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PROMISE_EXCEEDS_OUTSTANDING");
    }

    @Test
    void cancelledPromiseCanNoLongerBeEdited() {
        CreditPaymentPromise cancelled = promise("200.00", TODAY, TODAY.plusDays(3));
        cancelled.setStatus(PromiseStatus.CANCELLED);
        givenOpenPromise(cancelled);

        assertThatThrownBy(() -> promiseService.editPromise(PUMP_ID, 50L,
                new EditPromiseRequest(new BigDecimal("100.00"), TODAY.plusDays(4), null, null), user()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PROMISE_NOT_OPEN");
    }

    @Test
    void promiseIsKeptOnceApprovedPaymentsFromItsRecordingReachTheAmount() {
        CreditPaymentPromise promise = givenOpenPromiseToResolve("100.00", TODAY.minusDays(1), TODAY.plusDays(2));
        givenPayments(payment("150.00", TODAY, ExpenseApprovalStatus.APPROVED));
        givenGroupOwes("150.00");

        assertThat(promiseService.resolvePromises(PUMP_ID)).isZero();

        assertThat(promise.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(promise.getPaidAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void promiseIsKeptOnceTheAccountOwesNothingEvenIfPaidBeforeItWasRecorded() {
        CreditPaymentPromise promise = givenOpenPromiseToResolve("300.00", TODAY.plusDays(1), TODAY.plusDays(3));
        givenPayments(payment("300.00", TODAY, ExpenseApprovalStatus.APPROVED));
        givenGroupOwes("0.00");

        promiseService.resolvePromises(PUMP_ID);

        assertThat(promise.getStatus()).isEqualTo(PromiseStatus.KEPT);
        assertThat(promise.getPaidAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void promiseShortOfItsAmountAfterItsDateIsMissedAndTheManagersAreTold() {
        CreditPaymentPromise promise = givenOpenPromiseToResolve("200.00", TODAY.minusDays(4), TODAY.minusDays(1));
        givenPayments(payment("50.00", TODAY.minusDays(2), ExpenseApprovalStatus.APPROVED),
                payment("500.00", TODAY.minusDays(5), ExpenseApprovalStatus.APPROVED));
        givenGroupOwes("250.00");

        assertThat(promiseService.resolvePromises(PUMP_ID)).isEqualTo(1);

        assertThat(promise.getStatus()).isEqualTo(PromiseStatus.MISSED);
        assertThat(promise.getPaidAmount()).isEqualByComparingTo("50.00");
        verify(notificationDedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.CREDIT_PROMISE_MISSED),
                eq("CREDIT_PROMISE_MISSED:client:1:promise:50"), anyString(), anyString(), anyMap());
    }

    @Test
    void paymentAwaitingApprovalKeepsAPassedPromiseOpen() {
        CreditPaymentPromise promise = givenOpenPromiseToResolve("200.00", TODAY.minusDays(1), TODAY.minusDays(1));
        givenPayments(payment("50.00", TODAY.minusDays(1), ExpenseApprovalStatus.APPROVED),
                payment("150.00", TODAY.minusDays(1), ExpenseApprovalStatus.PENDING_APPROVAL));
        givenGroupOwes("250.00");

        assertThat(promiseService.resolvePromises(PUMP_ID)).isZero();

        assertThat(promise.getStatus()).isEqualTo(PromiseStatus.OPEN);
        verify(notificationDedupService, never()).maybeInsert(any(), any(), any(), any(), any(), any());
    }

    private PromiseResponse record(String amount, LocalDate promisedDate) {
        return promiseService.recordPromise(PUMP_ID, 2L,
                new RecordPromiseRequest(new BigDecimal(amount), promisedDate, null), user());
    }

    private void givenOpenPromise(CreditPaymentPromise promise) {
        when(promiseRepository.findByIdAndPumpId(50L, PUMP_ID)).thenReturn(Optional.of(promise));
    }

    private CreditPaymentPromise givenOpenPromiseToResolve(String amount, LocalDate recordedOn, LocalDate promisedDate) {
        CreditPaymentPromise promise = promise(amount, recordedOn, promisedDate);
        when(promiseRepository.findByPumpIdAndStatus(PUMP_ID, PromiseStatus.OPEN)).thenReturn(List.of(promise));
        return promise;
    }

    private void givenPayments(CreditPayment... payments) {
        when(paymentRepository.findApprovedOrPendingByClientIdInPaidFrom(any(), any())).thenReturn(List.of(payments));
    }

    private void givenGroupOwes(String outstanding) {
        when(interestService.computeOutstandingBatch(any())).thenReturn(Map.of(1L, new BigDecimal(outstanding)));
    }

    private static CreditPayment payment(String amount, LocalDate paidAt, ExpenseApprovalStatus status) {
        return CreditPayment.builder().clientId(1L).amount(new BigDecimal(amount)).paidAt(paidAt)
                .paymentApprovalStatus(status).build();
    }

    private static CreditPaymentPromise promise(String amount, LocalDate recordedOn, LocalDate promisedDate) {
        return CreditPaymentPromise.builder().id(50L).pumpId(PUMP_ID).clientId(1L).amount(new BigDecimal(amount))
                .recordedOn(recordedOn).promisedDate(promisedDate).recordedByUserId(3L).build();
    }

    private static User user() {
        User user = new User();
        user.setId(3L);
        user.setFullName("Ravi Manager");
        return user;
    }
}
