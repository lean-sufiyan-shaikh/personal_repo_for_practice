package com.ppms.collection;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One reminder given to an overdue client, at a stage of one overdue episode. */
@Entity
@Table(name = "credit_reminders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** First overdue day of the episode; a payment that ends the episode starts a new one with a later date. */
    @Column(name = "overdue_since", nullable = false)
    private LocalDate overdueSince;

    @Column(name = "stage_days", nullable = false)
    private Integer stageDays;

    @Column(name = "days_overdue", nullable = false)
    private Integer daysOverdue;

    @Column(name = "outstanding", nullable = false)
    private BigDecimal outstanding;

    /** The ReminderChannel that carried it. */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    private ReminderMethod method;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "reminded_by_user_id", nullable = false)
    private Long remindedByUserId;

    @Column(name = "reminded_at", nullable = false, updatable = false)
    private OffsetDateTime remindedAt;

    @PrePersist
    protected void onCreate() {
        remindedAt = OffsetDateTime.now();
    }
}
