package com.ppms.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** @param bounceCharge null when no bounce charge was added */
public record PaymentReversalResponse(Long id, Long paymentId, Long clientId, BigDecimal amount, BigDecimal bounceCharge,
                                      LocalDate reversalDate, String reason, Long reversedByUserId,
                                      OffsetDateTime createdAt) {

    static PaymentReversalResponse of(CreditPaymentReversal reversal) {
        return new PaymentReversalResponse(reversal.getId(), reversal.getPaymentId(), reversal.getClientId(),
                reversal.getAmount(), reversal.getBounceCharge(), reversal.getReversalDate(), reversal.getReason(),
                reversal.getReversedByUserId(), reversal.getCreatedAt());
    }
}
