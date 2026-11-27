package com.ppms.shift;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/** One correction of a closed shift: its figures before and after, who made it and why. Never edited. */
@Entity
@Table(name = "shift_amendments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftAmendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_figures", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> beforeFigures;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_figures", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> afterFigures;

    @Column(name = "amended_by_user_id", nullable = false)
    private Long amendedByUserId;

    @Column(name = "amended_at", nullable = false)
    private OffsetDateTime amendedAt;
}
