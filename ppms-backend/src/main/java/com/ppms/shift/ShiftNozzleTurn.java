package com.ppms.shift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** One operator working one nozzle of a shift between two meter readings; the litres in between are theirs. */
@Entity
@Table(name = "shift_nozzle_turns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftNozzleTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "nozzle_id", nullable = false)
    private Long nozzleId;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "start_reading", nullable = false)
    private BigDecimal startReading;

    @Column(name = "end_reading")
    private BigDecimal endReading;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "start_reason", nullable = false)
    private NozzleTurnStartReason startReason;

    @Column(name = "relief_id")
    private Long reliefId;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    public boolean isOpen() {
        return endedAt == null;
    }
}
