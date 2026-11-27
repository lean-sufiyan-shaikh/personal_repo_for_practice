package com.ppms.monthclose;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** The month summary as it stood at one close, and who re-opened that close and why; the summary never changes. */
@Entity
@Table(name = "month_close_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthCloseVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "month_close_id", nullable = false, updatable = false)
    private Long monthCloseId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb", nullable = false, updatable = false)
    private MonthSummary summary;

    @Column(name = "closed_by_user_id", nullable = false, updatable = false)
    private Long closedByUserId;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private OffsetDateTime closedAt;

    @Column(name = "reopened_by_user_id")
    private Long reopenedByUserId;

    @Column(name = "reopened_at")
    private OffsetDateTime reopenedAt;

    @Column(name = "reopen_reason")
    private String reopenReason;
}
