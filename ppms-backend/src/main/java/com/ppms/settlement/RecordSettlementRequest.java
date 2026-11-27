package com.ppms.settlement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RecordSettlementRequest {
    @NotNull(message = "paymentType is required (UPI, CARD or FLEET_CARD).")
    private SettlementPaymentType paymentType;
    @NotNull(message = "settlementDate is required.")
    @PastOrPresent(message = "settlementDate cannot be in the future.")
    private LocalDate settlementDate;
    /** The typed amount: gross or net according to amountBasis. */
    @NotNull(message = "amountReceived is required.")
    @DecimalMin(value = "0.01", message = "amountReceived must be more than zero.")
    private BigDecimal amountReceived;
    /** GROSS when omitted, so callers that typed the cleared amount keep working. */
    private SettlementAmountBasis amountBasis;
    /** Typed MDR; when absent the pump's MDR % is applied. */
    @DecimalMin(value = "0.00", message = "mdrAmount cannot be negative.")
    private BigDecimal mdrAmount;
    /** Typed GST on MDR; when absent with a typed MDR, the pump's GST % is applied to it. */
    @DecimalMin(value = "0.00", message = "gstOnMdrAmount cannot be negative.")
    private BigDecimal gstOnMdrAmount;
    private String notes;
    @Size(max = 100, message = "bankReference must be at most 100 characters.")
    private String bankReference;
}
