package com.ppms.monthclose;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "month_closes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthClose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    /** The first day of the month. */
    @Column(name = "month_start", nullable = false)
    private LocalDate monthStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MonthCloseStatus status;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
