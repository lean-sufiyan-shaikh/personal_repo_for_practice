package com.ppms.dayclose;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "day_close_comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DayCloseComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "day_close_id", nullable = false)
    private Long dayCloseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private DayCloseCommentKind kind;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
