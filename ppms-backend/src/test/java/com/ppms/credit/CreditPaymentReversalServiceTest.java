package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.support.FixedBusinessClock;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditPaymentReversalServiceTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 6);

    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final CreditPaymentRepository paymentRepository = mock(CreditPaymentRepository.class);
    private final CreditPaymentReversalRepository reversalRepository = mock(CreditPaymentReversalRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationDedupService notificationDedupService = mock(NotificationDedupService.class);

    private final CreditPaymentReversalService reversalService = new CreditPaymentReversalService(clientRepository,
            paymentRepository, reversalRepository, mock(CreditAccountPolicyService.class),
            mock(CreditInterestService.class), mock(ClosedDayGuard.class), auditService, notificationDedupService,
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")),
            mock(BalanceSheetStalenessService.class));

    private final CreditPayment payment = CreditPayment.builder().id(9L).pumpId(PUMP_ID).clientId(1L)
            .amount(new BigDecimal("4000.00")).paymentMode(PaymentMode.CHEQUE).paidAt(TODAY.minusDays(21)).build();

    @BeforeEach
    void givenApprovedPayment() {
        when(paymentRepository.findClientIdByIdAndPumpId(9L, PUMP_ID)).thenReturn(Optional.of(1L));
        when(paymentRepository.findById(9L)).thenReturn(Optional.of(payment));
        when(clientRepository.findById(1L)).thenReturn(Optional.of(
                CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Fleet Co").build()));
        when(reversalRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void reversalIsDatedTodayWithItsBounceChargeAndKeepsThePaymentAsReversed() {
        CreditPaymentReversal reversal = reverse(" Cheque bounced ", new BigDecimal("250"));

        assertThat(reversal.getReversalDate()).isEqualTo(TODAY);
        assertThat(reversal.getAmount()).isEqualByComparingTo("4000.00");
        assertThat(reversal.getBounceCharge()).isEqualByComparingTo("250.00");
        assertThat(reversal.getReason()).isEqualTo("Cheque bounced");
        assertThat(payment.getPaymentApprovalStatus()).isEqualTo(ExpenseApprovalStatus.REVERSED);
    }

    @Test
    void reversalIsAuditedAndNotifiesTheManagers() {
        User owner = owner();

        reversalService.reverse(PUMP_ID, 9L, new ReversePaymentRequest("Cheque bounced", null), owner);

        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.CREDIT_PAYMENT_REVERSED), eq("CreditPayment"), eq("9"),
                contains("reason=Cheque bounced"), eq(owner));
        verify(notificationDedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.CREDIT_PAYMENT_REVERSED),
                eq("CREDIT_PAYMENT_REVERSED:client:1:payment:9"), anyString(), anyString(), anyMap());
    }

    @Test
    void reversalNeedsAReason() {
        assertThatThrownBy(() -> reverse(" ", null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_REVERSAL_REASON_REQUIRED");
    }

    @Test
    void paymentCannotBeReversedTwice() {
        payment.setPaymentApprovalStatus(ExpenseApprovalStatus.REVERSED);

        assertThatThrownBy(() -> reverse("Again", null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_ALREADY_REVERSED");
        verify(reversalRepository, never()).save(any());
    }

    @Test
    void paymentWaitingForApprovalIsNotReversed() {
        payment.setPaymentApprovalStatus(ExpenseApprovalStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> reverse("Wrong", null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_NOT_REVERSIBLE");
    }

    @Test
    void paymentMatchedToABankLineIsNotReversed() {
        when(paymentRepository.isMatchedToBankLine(9L)).thenReturn(true);

        assertThatThrownBy(() -> reverse("Cheque bounced", null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_MATCHED_TO_BANK_LINE");
    }

    private CreditPaymentReversal reverse(String reason, BigDecimal bounceCharge) {
        return reversalService.reverse(PUMP_ID, 9L, new ReversePaymentRequest(reason, bounceCharge), owner());
    }

    private static User owner() {
        User owner = new User();
        owner.setId(3L);
        owner.setFullName("Owner");
        return owner;
    }
}
