package com.ppms.settlement;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Money taken back from card, UPI or fleet card collections. It lowers what reached the bank and is a cost of the day
 * it happened; the gross a settlement cleared stays as it was, so wallets and coverage are untouched.
 */
@Entity
@Table(name = "settlement_chargebacks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementChargeback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private ChargebackKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false)
    private SettlementPaymentType paymentType;

    @Column(name = "chargeback_date", nullable = false)
    private LocalDate chargebackDate;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "reference")
    private String reference;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "collection_date")
    private LocalDate collectionDate;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
