package com.ppms.credit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A debit the client disputes. While OPEN it stays in the balance but is left out of the overdue days, the credit
 * hold, reminders and interest. ACCEPTED puts it back as a normal debit; CREDITED settles it with a credit note.
 */
@Entity
@Table(name = "credit_disputes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "debit_type", nullable = false, length = 20)
    private CreditDebitType debitType;

    @Column(name = "debit_id", nullable = false)
    private Long debitId;

    @Column(name = "debit_date", nullable = false)
    private LocalDate debitDate;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private CreditDisputeStatus status = CreditDisputeStatus.OPEN;

    @Column(name = "raised_by", nullable = false)
    private Long raisedBy;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private OffsetDateTime raisedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    public boolean isOpen() {
        return status == CreditDisputeStatus.OPEN;
    }

    @PrePersist
    protected void onCreate() {
        raisedAt = OffsetDateTime.now();
    }
}
