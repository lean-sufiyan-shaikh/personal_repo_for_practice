package com.ppms.adjustment;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A manual meter reading correction on a nozzle (RESET or CUSTOM_READING). Moving the meter back is a plain audited
 * correction. Moving it forward means fuel left the nozzle without a sale: those unbilled litres are valued at the
 * day's price and taken out of tank stock through a drain record, and a manager's request waits for an Owner or Admin.
 */
@Entity
@Table(name = "nozzle_reading_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NozzleReadingAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "nozzle_id", nullable = false)
    private Long nozzleId;

    /** RESET or CUSTOM_READING */
    @Column(name = "adjustment_type", nullable = false)
    private String adjustmentType;

    @Column(name = "fuel_type", nullable = false)
    private String fuelType;

    @Column(name = "previous_reading", nullable = false)
    private BigDecimal previousReading;

    @Column(name = "new_reading", nullable = false)
    private BigDecimal newReading;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MeterAdjustmentStatus status = MeterAdjustmentStatus.APPLIED;

    /** Forward adjustments only: litres the meter moved on without a sale. */
    @Column(name = "unbilled_litres")
    private BigDecimal unbilledLitres;

    @Column(name = "price_per_litre")
    private BigDecimal pricePerLitre;

    /** unbilledLitres at the selling price of the day. */
    @Column(name = "unbilled_value")
    private BigDecimal unbilledValue;

    /** The drain that took the unbilled litres out of tank stock, once applied. */
    @Column(name = "fuel_dip_entry_id")
    private Long fuelDipEntryId;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
