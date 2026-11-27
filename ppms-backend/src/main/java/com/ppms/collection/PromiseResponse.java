package com.ppms.collection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** @param paidAmount approved payments since the promise was recorded, as of its resolution; null while open */
public record PromiseResponse(Long id, Long clientId, String clientName, BigDecimal amount, LocalDate promisedDate,
                              LocalDate recordedOn, String note, PromiseStatus status, BigDecimal paidAmount,
                              String recordedByName, OffsetDateTime recordedAt, OffsetDateTime resolvedAt) {

    static PromiseResponse of(CreditPaymentPromise promise, String clientName, String recordedByName) {
        return new PromiseResponse(promise.getId(), promise.getClientId(), clientName, promise.getAmount(),
                promise.getPromisedDate(), promise.getRecordedOn(), promise.getNote(), promise.getStatus(),
                promise.getPaidAmount(), recordedByName, promise.getRecordedAt(), promise.getResolvedAt());
    }
}
