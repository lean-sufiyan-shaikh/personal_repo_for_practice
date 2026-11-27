package com.ppms.pettycash;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A pump's petty cash box: the imprest float it is topped up to and how far a count may differ unnoticed. */
@Entity
@Table(name = "petty_cash_boxes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PettyCashBox {

    public static final BigDecimal DEFAULT_COUNT_TOLERANCE = new BigDecimal("10.00");

    @Id
    @Column(name = "pump_id")
    private Long pumpId;

    @Column(name = "float_amount", nullable = false)
    private BigDecimal floatAmount;

    @Column(name = "count_tolerance", nullable = false)
    private BigDecimal countTolerance;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    static PettyCashBox defaultsFor(Long pumpId) {
        return PettyCashBox.builder()
                .pumpId(pumpId)
                .floatAmount(BigDecimal.ZERO.setScale(2))
                .countTolerance(DEFAULT_COUNT_TOLERANCE)
                .build();
    }
}
