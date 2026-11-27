package com.ppms.cash;

import com.ppms.shift.DiscrepancyType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A physical count of the safe compared with the ledger balance at that moment, or at the end of countForDate when the
 * count is recorded late for an earlier business day. A count only records the difference; it never moves money, so a
 * shortage stays visible until someone records the matching cash movement.
 */
@Entity
@Table(name = "safe_counts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SafeCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "counted_amount", nullable = false)
    private BigDecimal countedAmount;

    @Column(name = "expected_amount", nullable = false)
    private BigDecimal expectedAmount;

    /** counted minus expected: negative when the safe is short. */
    @Column(name = "variance_amount", nullable = false)
    private BigDecimal varianceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "variance_type")
    private DiscrepancyType varianceType;

    /** The pump's tolerance when the count was taken, so later setting changes keep history. */
    @Column(name = "tolerance_amount", nullable = false)
    private BigDecimal toleranceAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "denominations", columnDefinition = "jsonb")
    private CashDenominations denominations;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "counted_by_user_id", nullable = false)
    private Long countedByUserId;

    @Column(name = "counted_at", nullable = false)
    private OffsetDateTime countedAt;

    @Column(name = "count_for_date", nullable = false)
    private LocalDate countForDate;

    /** True when recorded after countForDate had ended: the next morning, or back-dated by an owner or admin. */
    @Column(name = "late", nullable = false)
    private boolean late;

    /** Only set on a count back-dated by an owner or admin after the morning cut-off. */
    @Column(name = "back_date_reason", length = 500)
    private String backDateReason;
}
