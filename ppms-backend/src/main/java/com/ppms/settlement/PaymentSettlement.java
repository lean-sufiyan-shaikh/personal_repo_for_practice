package com.ppms.settlement;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Records a single digital payment settlement — the actual bank credit for a given day.
 *
 * settlement_date is the date the funds arrived in the bank account (not when this record
 * was created). Partial settlements are allowed: Admin can record ₹5,000 settled on Monday
 * and ₹3,000 on Tuesday for the same period's collections.
 *
 * The "wallet balance" for a payment type = SUM(all shift collections) − SUM(all ACTIVE settlements' gross).
 * amount_received is the gross cleared from collections; the bank's charges (MDR and GST on it) come off it,
 * leaving net_amount, the money that reached the account.
 */
@Entity
@Table(name = "payment_settlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSettlement {

    public static final String ACTIVE = "ACTIVE";
    public static final String VOIDED = "VOIDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "payment_type", nullable = false)
    private SettlementPaymentType paymentType;

    /** Date the funds arrived in the bank — set explicitly by the Admin/Owner. */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "amount_received", nullable = false)
    private BigDecimal amountReceived;

    @Column(name = "mdr_amount", nullable = false)
    @Builder.Default
    private BigDecimal mdrAmount = BigDecimal.ZERO;

    @Column(name = "gst_on_mdr_amount", nullable = false)
    @Builder.Default
    private BigDecimal gstOnMdrAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount;

    /** First and last collection day this settlement cleared, oldest first. Null for settlements before V49. */
    @Column(name = "covers_from_date")
    private LocalDate coversFromDate;

    @Column(name = "covers_to_date")
    private LocalDate coversToDate;

    /** True when the last collection day it reached was only partly cleared. Null for settlements before V49. */
    @Column(name = "is_partial")
    private Boolean partial;

    @Column(name = "notes")
    private String notes;

    /** Bank reference or UTR of the credit; unique per pump among active settlements. */
    @Column(name = "bank_reference")
    private String bankReference;

    /**
     * Cumulative wallet balance for this payment type at the moment this record was saved.
     * Null for settlements recorded before this field was introduced.
     * Settlements recorded before V49 show as partial when amountReceived < pendingAtRecordTime.
     */
    @Column(name = "pending_at_record_time")
    private BigDecimal pendingAtRecordTime;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    // ACTIVE by default; VOIDED settlements stay in history but are excluded from every total.
    @Column(name = "void_status", nullable = false)
    @Builder.Default
    private String voidStatus = ACTIVE;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "voided_by_user_id")
    private Long voidedByUserId;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (netAmount == null) {
            netAmount = amountReceived.subtract(mdrAmount).subtract(gstOnMdrAmount);
        }
    }

    public BigDecimal chargesAmount() {
        return mdrAmount.add(gstOnMdrAmount);
    }
}
