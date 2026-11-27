package com.ppms.credit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Credit an Owner or Admin let through at shift close for an account that was over its limit, on hold or overdue. */
@Entity
@Table(name = "credit_close_exceptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditCloseException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "shift_id")
    private Long shiftId;

    /** The account the limit and billing cycle are checked on: the client itself, or the parent of a sub-account. */
    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "blocked_reason", nullable = false, length = 20)
    private CreditStatus blockedReason;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "outstanding", nullable = false)
    private BigDecimal outstanding;

    /** Null when the account has unlimited credit. */
    @Column(name = "effective_limit")
    private BigDecimal effectiveLimit;

    @Column(name = "days_overdue", nullable = false)
    private int daysOverdue;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "allowed_by_user_id", nullable = false)
    private Long allowedByUserId;

    @Column(name = "allowed_at", nullable = false, updatable = false)
    private OffsetDateTime allowedAt;

    @PrePersist
    protected void onCreate() {
        allowedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
