package com.ppms.credit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "credit_limit_changes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditLimitChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "old_limit", nullable = false)
    private BigDecimal oldLimit;

    @Column(name = "new_limit", nullable = false)
    private BigDecimal newLimit;

    @Column(name = "old_unlimited", nullable = false)
    private boolean oldUnlimited;

    @Column(name = "new_unlimited", nullable = false)
    private boolean newUnlimited;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        changedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
