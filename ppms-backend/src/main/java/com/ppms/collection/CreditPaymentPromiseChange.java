package com.ppms.collection;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One edit or the cancellation of a promise to pay, with what the promise was before it. */
@Entity
@Table(name = "credit_payment_promise_changes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditPaymentPromiseChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "promise_id", nullable = false)
    private Long promiseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 10)
    private PromiseChangeType changeType;

    @Column(name = "previous_amount", nullable = false)
    private BigDecimal previousAmount;

    @Column(name = "previous_promised_date", nullable = false)
    private LocalDate previousPromisedDate;

    /** Null for a cancellation. */
    @Column(name = "new_amount")
    private BigDecimal newAmount;

    /** Null for a cancellation. */
    @Column(name = "new_promised_date")
    private LocalDate newPromisedDate;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;

    @PrePersist
    protected void onCreate() {
        changedAt = OffsetDateTime.now();
    }
}
