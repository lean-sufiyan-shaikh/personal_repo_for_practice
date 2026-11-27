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

import java.time.OffsetDateTime;

/** An operator working a shift besides the one it was opened for; leftAt is set when they leave or the shift closes. */
@Entity
@Table(name = "shift_operators")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftOperator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private ShiftOperatorRole role;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    @Column(name = "added_by_user_id", nullable = false)
    private Long addedByUserId;

    public boolean isActive() {
        return leftAt == null;
    }
}
