package com.ppms.credit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A credit note, a bad-debt write-off, a security deposit applied to the dues, or an advance paid back. A credit note
 * may name the debit it credits; it then clears that debit before the account's oldest bills.
 */
@Entity
@Table(name = "credit_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CreditAdjustmentKind kind;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "adjustment_date", nullable = false)
    private LocalDate adjustmentDate;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "debit_type", length = 20)
    private CreditDebitType debitType;

    @Column(name = "debit_id")
    private Long debitId;

    @Column(name = "debit_date")
    private LocalDate debitDate;

    @Column(name = "returned_quantity")
    private BigDecimal returnedQuantity;

    @Column(name = "dispute_id")
    private Long disputeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_mode", length = 20)
    private PaymentMode refundMode;

    @Column(name = "refund_reference", length = 100)
    private String refundReference;

    @Column(name = "cash_event_id")
    private Long cashEventId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Negative when the row lowers what the account owes. */
    public BigDecimal signedAmount() {
        return kind.lowersBalance() ? amount.negate() : amount;
    }

    /**
     * The day the row starts to change the principal interest is charged on: a disputed debit credited after the
     * dispute was wrong from its own date, so it never bore interest.
     */
    public LocalDate principalDate() {
        return disputeId != null && debitDate != null ? debitDate : adjustmentDate;
    }

    public boolean creditsDebit() {
        return debitType != null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
