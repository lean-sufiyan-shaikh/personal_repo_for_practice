package com.ppms.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** What the credit desk endpoints return. */
public final class CreditDeskResponses {

    private CreditDeskResponses() {
    }

    /** @param gstCreditNoteId set when the credit note also produced a GST credit note */
    public record AdjustmentResponse(Long id, Long clientId, CreditAdjustmentKind kind, BigDecimal amount,
                                     LocalDate adjustmentDate, String reason, CreditDebitType debitType, Long debitId,
                                     BigDecimal returnedQuantity, PaymentMode refundMode, Long gstCreditNoteId,
                                     String gstCreditNoteNumber) {

        public static AdjustmentResponse of(CreditAdjustment adjustment) {
            return of(adjustment, null, null);
        }

        public static AdjustmentResponse of(CreditAdjustment adjustment, Long gstCreditNoteId, String gstCreditNoteNumber) {
            return new AdjustmentResponse(adjustment.getId(), adjustment.getClientId(), adjustment.getKind(),
                    adjustment.getAmount(), adjustment.getAdjustmentDate(), adjustment.getReason(),
                    adjustment.getDebitType(), adjustment.getDebitId(), adjustment.getReturnedQuantity(),
                    adjustment.getRefundMode(), gstCreditNoteId, gstCreditNoteNumber);
        }
    }

    public record DepositResponse(Long id, Long clientId, CreditDepositKind kind, BigDecimal amount,
                                  LocalDate movementDate, PaymentMode paymentMode, String referenceNo, String reason) {

        public static DepositResponse of(CreditDeposit deposit) {
            return new DepositResponse(deposit.getId(), deposit.getClientId(), deposit.getKind(), deposit.getAmount(),
                    deposit.getMovementDate(), deposit.getPaymentMode(), deposit.getReferenceNo(), deposit.getReason());
        }
    }

    public record DisputeResponse(Long id, Long clientId, CreditDebitType debitType, Long debitId, LocalDate debitDate,
                                  BigDecimal amount, String reason, CreditDisputeStatus status,
                                  OffsetDateTime raisedAt, OffsetDateTime resolvedAt, String resolutionNote) {

        public static DisputeResponse of(CreditDispute dispute) {
            return new DisputeResponse(dispute.getId(), dispute.getClientId(), dispute.getDebitType(),
                    dispute.getDebitId(), dispute.getDebitDate(), dispute.getAmount(), dispute.getReason(),
                    dispute.getStatus(), dispute.getRaisedAt(), dispute.getResolvedAt(), dispute.getResolutionNote());
        }
    }

    /** @param amount null writes off all the account owes */
    public record WriteOffRequest(
            @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
            @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
            BigDecimal amount,
            @Size(max = 500, message = "Reason must be at most 500 characters") String reason) {}

    public record ReasonRequest(@Size(max = 500, message = "Reason must be at most 500 characters") String reason) {}
}
