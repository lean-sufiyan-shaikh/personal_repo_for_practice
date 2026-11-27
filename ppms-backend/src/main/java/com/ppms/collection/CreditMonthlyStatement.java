package com.ppms.collection;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** The statement of one client for one month, stored as it was printed so it reads the same every time. */
@Entity
@Table(name = "credit_monthly_statements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditMonthlyStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "closing_balance", nullable = false)
    private BigDecimal closingBalance;

    @Column(name = "transaction_count", nullable = false)
    private Integer transactionCount;

    @Column(name = "html", nullable = false, columnDefinition = "TEXT")
    private String html;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private OffsetDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        generatedAt = OffsetDateTime.now();
    }
}
