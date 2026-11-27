package com.ppms.pettycash;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One movement of the petty cash box; amount is positive for money in and negative for money out. */
@Entity
@Table(name = "petty_cash_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PettyCashEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private PettyCashEntryType entryType;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "expense_id")
    private Long expenseId;

    /** The safe's cash-out that funded a top-up. */
    @Column(name = "safe_cash_event_id")
    private Long safeCashEventId;

    @Column(name = "reverses_entry_id")
    private Long reversesEntryId;

    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
