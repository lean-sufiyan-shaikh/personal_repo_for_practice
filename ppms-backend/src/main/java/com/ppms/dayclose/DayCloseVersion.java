package com.ppms.dayclose;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** The summary as it stood at one close; never changed afterwards. */
@Entity
@Table(name = "day_close_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DayCloseVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "day_close_id", nullable = false, updatable = false)
    private Long dayCloseId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb", nullable = false, updatable = false)
    private DaySummary summary;

    @Column(name = "closed_by_user_id", nullable = false, updatable = false)
    private Long closedByUserId;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private OffsetDateTime closedAt;
}
