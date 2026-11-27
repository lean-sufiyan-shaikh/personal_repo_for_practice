package com.ppms.settlement;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data @Builder
public class SettlementResponse {
    private Long   id;
    private Long   pumpId;
    private String paymentType;
    private String settlementDate;
    /** Same as grossAmount; kept for existing callers. */
    private BigDecimal amountReceived;
    private BigDecimal grossAmount;
    private BigDecimal mdrAmount;
    private BigDecimal gstOnMdrAmount;
    private BigDecimal chargesAmount;
    private BigDecimal netAmount;
    /** Chargebacks and refunds taken against this settlement. */
    private BigDecimal chargebacksAmount;
    /** netAmount less chargebacksAmount: what this settlement finally left in the bank. */
    private BigDecimal netAfterChargebacks;
    /** First and last collection day this settlement cleared, oldest first. Null for older settlements. */
    private String coversFromDate;
    private String coversToDate;
    private String notes;
    private String bankReference;
    private String recordedByUserName;
    private String createdAt;
    /** Wallet balance for this payment type at the time of recording. Null for legacy entries. */
    private BigDecimal pendingAtRecordTime;
    /** True when the last collection day the settlement reached was only partly cleared. */
    @JsonProperty("isPartial")
    private boolean isPartial;
    /** ACTIVE or VOIDED. Voided settlements are excluded from wallet and daily totals. */
    private String voidStatus;
    private String voidReason;
    private String voidedAt;
}
