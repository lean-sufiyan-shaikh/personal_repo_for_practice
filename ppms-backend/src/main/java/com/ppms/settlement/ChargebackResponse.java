package com.ppms.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ChargebackResponse(Long id, ChargebackKind kind, SettlementPaymentType paymentType, LocalDate chargebackDate,
                                 BigDecimal amount, String reference, String reason, Long settlementId,
                                 LocalDate collectionDate, String recordedByUserName, OffsetDateTime createdAt) {

    static ChargebackResponse of(SettlementChargeback chargeback, String recordedByUserName) {
        return new ChargebackResponse(chargeback.getId(), chargeback.getKind(), chargeback.getPaymentType(),
                chargeback.getChargebackDate(), chargeback.getAmount(), chargeback.getReference(), chargeback.getReason(),
                chargeback.getSettlementId(), chargeback.getCollectionDate(), recordedByUserName, chargeback.getCreatedAt());
    }
}
