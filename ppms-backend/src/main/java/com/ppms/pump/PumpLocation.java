package com.ppms.pump;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "pump_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PumpLocation {

    public static final BigDecimal DEFAULT_RECEIPT_TOLERANCE_PERCENT = new BigDecimal("0.20");
    public static final BigDecimal DEFAULT_DENSITY_TOLERANCE = new BigDecimal("3.0");
    public static final BigDecimal DEFAULT_SAFE_COUNT_TOLERANCE = new BigDecimal("10.00");
    public static final LocalTime DEFAULT_SAFE_COUNT_CUTOFF_TIME = LocalTime.of(10, 0);
    public static final int DEFAULT_CREDIT_HOLD_DAYS = 45;
    public static final int DEFAULT_BACKDATED_PAYMENT_APPROVAL_DAYS = 7;
    public static final BigDecimal DEFAULT_MANAGER_CREDIT_LIMIT_CAP = new BigDecimal("50000.00");
    public static final int DEFAULT_NEAR_LIMIT_PERCENT = 90;
    public static final BigDecimal DEFAULT_ESCALATION_THRESHOLD = new BigDecimal("500.00");
    public static final int DEFAULT_EARLY_START_GRACE_MINUTES = 30;
    public static final BigDecimal DEFAULT_ROUNDING_TOLERANCE = new BigDecimal("1.00");
    public static final BigDecimal DEFAULT_TEST_VOLUME_LITRES = new BigDecimal("5.00");
    public static final BigDecimal DEFAULT_MAX_TEST_LITRES_PER_NOZZLE = new BigDecimal("10.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address", nullable = false)
    private String address;

    /** Maximum number of Dispensary Units (machines) allowed on this pump. Hard cap: 20. */
    @Column(name = "max_du_count", nullable = false)
    private Integer maxDuCount;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "admin_id")
    private Long adminId;

    /** Discrepancy amount above which the resolution requires Owner approval; zero escalates every discrepancy. */
    @Column(name = "discrepancy_escalation_threshold", nullable = false)
    @Builder.Default
    private BigDecimal discrepancyEscalationThreshold = DEFAULT_ESCALATION_THRESHOLD;

    /** Minutes before a shift window starts during which a shift may already be opened for that window. */
    @Column(name = "early_start_grace_minutes", nullable = false)
    @Builder.Default
    private int earlyStartGraceMinutes = DEFAULT_EARLY_START_GRACE_MINUTES;

    /** A shift whose collections differ from the amount due by at most this many rupees closes balanced. */
    @Column(name = "rounding_tolerance", nullable = false)
    @Builder.Default
    private BigDecimal roundingTolerance = DEFAULT_ROUNDING_TOLERANCE;

    /** The measure each nozzle is tested with, offered at close as the litres poured back into the tank. */
    @Column(name = "test_volume_litres", nullable = false)
    @Builder.Default
    private BigDecimal testVolumeLitres = DEFAULT_TEST_VOLUME_LITRES;

    /** Test litres above this on one nozzle in one shift need a note and are audited. */
    @Column(name = "max_test_litres_per_nozzle", nullable = false)
    @Builder.Default
    private BigDecimal maxTestLitresPerNozzle = DEFAULT_MAX_TEST_LITRES_PER_NOZZLE;

    /**
     * Expense amount above which an OPERATOR/MANAGER-submitted expense requires Owner/Admin approval.
     * Expenses at or below this threshold are auto-approved.
     * If null, all expenses are auto-approved.
     */
    @Column(name = "expense_approval_threshold")
    private BigDecimal expenseApprovalThreshold;

    /**
     * When false, this pump is hidden from the owner's dashboard.
     * Staff (ADMIN, MANAGER, OPERATOR) assigned directly to this pump are not affected —
     * they continue to access it via their assignedPumpId.
     * Only SuperAdmin can toggle this flag.
     */
    @Column(name = "gstin", length = 15)
    private String gstin;

    /** Registered legal name printed on tax invoices; the trade name stays in {@code name}. */
    @Column(name = "legal_name", length = 150)
    private String legalName;

    @Column(name = "state_name", length = 60)
    private String stateName;

    @Column(name = "invoice_prefix", length = 5)
    private String invoicePrefix;

    /** A delivery received short of its invoice by more than this share of the invoice litres raises a claim. */
    @Column(name = "receipt_tolerance_percent", nullable = false)
    @Builder.Default
    private BigDecimal receiptTolerancePercent = DEFAULT_RECEIPT_TOLERANCE_PERCENT;

    /** Observed and invoice densities (kg/m³) further apart than this are flagged. */
    @Column(name = "density_tolerance", nullable = false)
    @Builder.Default
    private BigDecimal densityTolerance = DEFAULT_DENSITY_TOLERANCE;

    /** A safe count further from the ledger balance than this many rupees notifies the owner. */
    @Column(name = "safe_count_tolerance", nullable = false)
    @Builder.Default
    private BigDecimal safeCountTolerance = DEFAULT_SAFE_COUNT_TOLERANCE;

    /** Until this time of the morning a manager may still record a safe count for yesterday. */
    @Column(name = "safe_count_cutoff_time", nullable = false)
    @Builder.Default
    private LocalTime safeCountCutoffTime = DEFAULT_SAFE_COUNT_CUTOFF_TIME;

    /** Days overdue at which an overdue credit client is due a reminder, ascending; empty turns reminders off. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reminder_stage_days", nullable = false)
    @Builder.Default
    private Integer[] reminderStageDays = {7, 15, 30};

    /** Days overdue at which new credit for the client is held; null turns the hold off. */
    @Column(name = "credit_hold_days")
    @Builder.Default
    private Integer creditHoldDays = DEFAULT_CREDIT_HOLD_DAYS;

    /** A manager's credit payment above this amount waits for Owner/Admin approval; null lets any amount through. */
    @Column(name = "credit_payment_approval_threshold")
    private BigDecimal creditPaymentApprovalThreshold;

    /** A manager's credit payment dated more than this many days before today waits for Owner/Admin approval. */
    @Column(name = "backdated_payment_approval_days", nullable = false)
    @Builder.Default
    private Integer backdatedPaymentApprovalDays = DEFAULT_BACKDATED_PAYMENT_APPROVAL_DAYS;

    /** Whether a parent account is invoiced once for its whole group, its sub-accounts included, unless chosen otherwise. */
    @Column(name = "group_invoice_by_default", nullable = false)
    @Builder.Default
    private boolean groupInvoiceByDefault = false;

    /** The credit limit a new client starts with; 0 means it can take no credit until a limit is set. */
    @Column(name = "default_credit_limit", nullable = false)
    @Builder.Default
    private BigDecimal defaultCreditLimit = BigDecimal.ZERO;

    /** The highest credit limit a manager may set; above it, or unlimited credit, needs an Owner or Admin. */
    @Column(name = "manager_credit_limit_cap", nullable = false)
    @Builder.Default
    private BigDecimal managerCreditLimitCap = DEFAULT_MANAGER_CREDIT_LIMIT_CAP;

    @Column(name = "near_limit_percent", nullable = false)
    @Builder.Default
    private int nearLimitPercent = DEFAULT_NEAR_LIMIT_PERCENT;

    /** Set together with upiPayeeName, or both null when the pump takes no UPI payments by link. */
    @Column(name = "upi_id", length = 100)
    private String upiId;

    @Column(name = "upi_payee_name", length = 100)
    private String upiPayeeName;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** A pump of sample data for trying PPMS: left out of all-pump totals and kept quiet by jobs and alerts. */
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

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
