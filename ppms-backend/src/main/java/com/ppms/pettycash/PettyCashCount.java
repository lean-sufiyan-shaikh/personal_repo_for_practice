package com.ppms.pettycash;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Cash counted in the petty cash box against what its ledger expected for that business day. */
@Entity
@Table(name = "petty_cash_counts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PettyCashCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "count_for_date", nullable = false)
    private LocalDate countForDate;

    @Column(name = "counted_amount", nullable = false)
    private BigDecimal countedAmount;

    @Column(name = "expected_amount", nullable = false)
    private BigDecimal expectedAmount;

    @Column(name = "variance_amount", nullable = false)
    private BigDecimal varianceAmount;

    @Column(name = "tolerance_amount", nullable = false)
    private BigDecimal toleranceAmount;

    @Column(name = "notes")
    private String notes;

    @Column(name = "counted_by_user_id", nullable = false)
    private Long countedByUserId;

    @Column(name = "counted_at", nullable = false)
    private OffsetDateTime countedAt;

    public boolean isBeyondTolerance() {
        return varianceAmount.abs().compareTo(toleranceAmount) > 0;
    }
}
