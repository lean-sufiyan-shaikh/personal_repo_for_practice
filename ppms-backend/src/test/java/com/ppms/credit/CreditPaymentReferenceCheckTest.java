package com.ppms.credit;

import com.ppms.common.exception.BusinessException;
import com.ppms.support.FixedBusinessClock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditPaymentReferenceCheckTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 6);

    private final CreditPaymentRepository paymentRepository = mock(CreditPaymentRepository.class);
    private final CreditPaymentReferenceCheck referenceCheck = new CreditPaymentReferenceCheck(paymentRepository,
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")));

    @ParameterizedTest
    @EnumSource(value = PaymentMode.class, names = {"UPI", "BANK_TRANSFER", "CHEQUE"})
    void upiBankTransferAndChequeNeedAReference(PaymentMode mode) {
        assertThatThrownBy(() -> referenceCheck.checkedReference(PUMP_ID, request(mode, " ")))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_PAYMENT_REFERENCE_REQUIRED");
                    assertThat(refusal.getParams()).containsEntry("modeCode", mode.name());
                });
    }

    @Test
    void cashNeedsNoReference() {
        assertThat(referenceCheck.checkedReference(PUMP_ID, request(PaymentMode.CASH, null))).isNull();
    }

    @Test
    void chequeDetailsOnACashPaymentAreRefused() {
        RecordPaymentRequest cash = request(PaymentMode.CASH, null);
        cash.setChequeDate(TODAY);

        assertThatThrownBy(() -> referenceCheck.checkedReference(PUMP_ID, cash))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_CHEQUE_DETAILS_NOT_CHEQUE");
    }

    @Test
    void referenceUsedInTheLastNinetyDaysIsAPossibleDuplicate() {
        when(paymentRepository.findSameReferenceSince(PUMP_ID, "UTR-777", TODAY.minusDays(90))).thenReturn(List.of(
                CreditPayment.builder().id(5L).amount(new BigDecimal("500.00")).paidAt(TODAY.minusDays(3)).build()));

        assertThatThrownBy(() -> referenceCheck.checkedReference(PUMP_ID, request(PaymentMode.BANK_TRANSFER, " UTR-777 ")))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_PAYMENT_POSSIBLE_DUPLICATE");
                    assertThat(refusal.getParams()).containsEntry("referenceNo", "UTR-777")
                            .containsEntry("paidAt", TODAY.minusDays(3).toString());
                });
    }

    @Test
    void confirmedDuplicateIsAcceptedWithItsReferenceTrimmed() {
        RecordPaymentRequest confirmed = request(PaymentMode.BANK_TRANSFER, " UTR-777 ");
        confirmed.setConfirmDuplicate(true);

        assertThat(referenceCheck.checkedReference(PUMP_ID, confirmed)).isEqualTo("UTR-777");
        verify(paymentRepository, never()).findSameReferenceSince(anyLong(), anyString(), any());
    }

    private static RecordPaymentRequest request(PaymentMode mode, String reference) {
        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setAmount(new BigDecimal("500.00"));
        request.setPaymentMode(mode);
        request.setPaidAt(TODAY);
        request.setReferenceNo(reference);
        return request;
    }
}
