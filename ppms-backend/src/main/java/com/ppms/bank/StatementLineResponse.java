package com.ppms.bank;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Value
@Builder
public class StatementLineResponse {
    Long id;
    Long importId;
    LocalDate txnDate;
    String narration;
    BigDecimal debitAmount;
    BigDecimal creditAmount;
    BigDecimal balance;
    String utrReference;
    String matchStatus;
    Long matchedShiftId;
    Long matchedAncillarySaleId;
    Long matchedPaymentId;
    Long matchedSettlementId;
    Long matchedDepositId;
    Long matchedSupplierPaymentId;
    /** The matched record, or for a SUGGESTED line the suggested one; null when the line links to none. */
    MatchCandidate matchedRecord;
    String matchNotes;
    OffsetDateTime createdAt;
}
