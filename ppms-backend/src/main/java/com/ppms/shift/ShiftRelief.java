package com.ppms.shift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A relief operator covering another operator's nozzles during a break; the shift stays open throughout. */
@Entity
@Table(name = "shift_reliefs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftRelief {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "relieved_operator_id", nullable = false)
    private Long relievedOperatorId;

    @Column(name = "relief_operator_id", nullable = false)
    private Long reliefOperatorId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "cash_handed_over")
    private BigDecimal cashHandedOver;

    @Column(name = "cash_handed_back")
    private BigDecimal cashHandedBack;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "started_by_user_id", nullable = false)
    private Long startedByUserId;

    @Column(name = "ended_by_user_id")
    private Long endedByUserId;

    public boolean isActive() {
        return endedAt == null;
    }
}
