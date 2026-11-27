package com.ppms.shift;

import com.ppms.cash.CashDenominations;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "shifts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    /** The Dispensary Unit (machine) this shift was operated on. */
    @Column(name = "du_id", nullable = false)
    private Long duId;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "opened_by_user_id", nullable = false)
    private Long openedByUserId;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    /**
     * FK to the pump_shift_definition that was active when this shift was opened.
     * Retained permanently so historical reports remain accurate even when the
     * admin later reconfigures the shift schedule.
     */
    @Column(name = "shift_definition_id")
    private Long shiftDefinitionId;

    /**
     * Snapshot of the definition's name at open time (e.g. "Night Shift").
     * Stored so the display name is preserved if the definition is later renamed.
     */
    @Column(name = "shift_name", length = 100)
    private String shiftName;

    /**
     * Snapshot of the definition's isNightShift flag at open time.
     * Used by payroll calculations to apply the correct hourly rate without
     * a join back to the definition table.
     */
    @Column(name = "is_night_shift", nullable = false)
    @Builder.Default
    private Boolean isNightShift = false;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "actual_start_time", nullable = false)
    private OffsetDateTime actualStartTime;

    /** Capped at the scheduled end for a late close, so DAY balance-sheet windows stay aligned with the definition. */
    @Column(name = "actual_end_time")
    private OffsetDateTime actualEndTime;

    /** When the closing readings were really entered; null for shifts closed before it was recorded. */
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    // ── Payment breakdown (reported by operator at close) ──────────────────

    @Column(name = "cash_collected")
    private BigDecimal cashCollected;

    @Column(name = "upi_collected")
    private BigDecimal upiCollected;

    @Column(name = "card_collected")
    private BigDecimal cardCollected;

    // Initialised to ZERO so Hibernate never sends an explicit NULL on INSERT.
    // The DB column is NOT NULL DEFAULT 0 but PostgreSQL only applies the default
    // when the column is omitted entirely — an explicit NULL overrides it.
    @Column(name = "fleet_card_collected")
    @Builder.Default
    private BigDecimal fleetCardCollected = BigDecimal.ZERO;

    @Column(name = "credit_total")
    private BigDecimal creditTotal;

    /** Confirmed cash drops of this shift at close; already part of cashCollected. Null for shifts closed before drops existed. */
    @Column(name = "cash_dropped")
    private BigDecimal cashDropped;

    /** The bag count by note at close; null when only a total was typed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cash_denominations", columnDefinition = "jsonb")
    private CashDenominations cashDenominations;

    // ── Blind count: collections counted and locked before the expected amount is shown ──

    @Column(name = "blind_cash_counted")
    private BigDecimal blindCashCounted;

    @Column(name = "blind_upi_counted")
    private BigDecimal blindUpiCounted;

    @Column(name = "blind_card_counted")
    private BigDecimal blindCardCounted;

    @Column(name = "blind_fleet_card_counted")
    private BigDecimal blindFleetCardCounted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blind_cash_denominations", columnDefinition = "jsonb")
    private CashDenominations blindCashDenominations;

    @Column(name = "blind_counted_at")
    private OffsetDateTime blindCountedAt;

    @Column(name = "blind_counted_by_user_id")
    private Long blindCountedByUserId;

    // ── Totals computed at close ───────────────────────────────────────────

    @Column(name = "total_amount_due")
    private BigDecimal totalAmountDue;

    // ── Discrepancy ────────────────────────────────────────────────────────

    @Column(name = "discrepancy_amount")
    private BigDecimal discrepancyAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "discrepancy_type")
    private DiscrepancyType discrepancyType;

    @Column(name = "discrepancy_reason")
    private String discrepancyReason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "discrepancy_resolution")
    private DiscrepancyResolution discrepancyResolution;

    @Column(name = "discrepancy_resolution_note")
    private String discrepancyResolutionNote;

    @Column(name = "discrepancy_resolved_by_id")
    private Long discrepancyResolvedById;

    @Column(name = "discrepancy_resolved_at")
    private OffsetDateTime discrepancyResolvedAt;

    @Column(name = "investigation_note")
    private String investigationNote;

    @Column(name = "investigation_started_by_id")
    private Long investigationStartedById;

    @Column(name = "investigation_started_at")
    private OffsetDateTime investigationStartedAt;

    /**
     * Cash the operator returned for a SHORT discrepancy, at most the discrepancy; any rest is resolved another way.
     * The balance sheet shows it as inbound cash on the day it was recovered.
     */
    @Column(name = "cash_recovery_amount")
    private BigDecimal cashRecoveryAmount;

    @Column(name = "cash_recovered_at")
    private OffsetDateTime cashRecoveredAt;

    /** Collected minus due when the difference was within the pump's rounding tolerance and the shift balanced. */
    @Column(name = "rounding_adjustment")
    private BigDecimal roundingAdjustment;

    /** True when the tanks' book stock could not cover what the shift sold at close. */
    @Column(name = "stock_shortfall", nullable = false)
    @Builder.Default
    private boolean stockShortfall = false;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private ShiftStatus status;

    @Builder.Default
    @Column(name = "is_overdue_flag", nullable = false)
    private Boolean isOverdueFlag = false;

    /**
     * True when this shift was entered retroactively by an Admin/Owner via the backfill flow.
     * The shift behaves identically to a live closed shift for reports and balance sheets.
     */
    @Column(name = "is_backfilled", nullable = false)
    @Builder.Default
    private boolean isBackfilled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** The shortage still to be settled by salary deduction or waiver once any cash recovery is taken off. */
    public BigDecimal shortageNotRecovered() {
        BigDecimal discrepancy = discrepancyAmount != null ? discrepancyAmount : BigDecimal.ZERO;
        return cashRecoveryAmount != null ? discrepancy.subtract(cashRecoveryAmount) : discrepancy;
    }

    public boolean hasBlindCount() {
        return blindCountedAt != null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
