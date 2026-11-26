package com.ppms.inventory;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A claim on the supplier for litres a delivery was short beyond the pump's tolerance. */
@Entity
@Table(name = "shortage_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortageClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "tanker_delivery_id", nullable = false)
    private Long tankerDeliveryId;

    @Column(name = "litres_short", nullable = false)
    private BigDecimal litresShort;

    /** The delivery's invoice cost per litre. */
    @Column(name = "price_per_litre", nullable = false)
    private BigDecimal pricePerLitre;

    @Column(name = "claim_amount", nullable = false)
    private BigDecimal claimAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ShortageClaimStatus status = ShortageClaimStatus.RAISED;

    @Column(name = "raised_by_user_id", nullable = false)
    private Long raisedByUserId;

    @Column(name = "credit_note_amount")
    private BigDecimal creditNoteAmount;

    @Column(name = "credit_note_reference", length = 100)
    private String creditNoteReference;

    @Column(name = "credit_note_date")
    private LocalDate creditNoteDate;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
