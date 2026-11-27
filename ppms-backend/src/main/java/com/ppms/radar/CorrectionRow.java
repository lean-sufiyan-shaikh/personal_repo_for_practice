package com.ppms.radar;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A void, reassignment or rejection: the person it is counted against, when it happened and what it was about. */
public record CorrectionRow(Long userId, OffsetDateTime happenedAt, BigDecimal amount, Long shiftId, Long referenceId,
                            String note) {

    /** For corrections that do not belong to a shift; JPQL cannot pass a typed null into a constructor. */
    public CorrectionRow(Long userId, OffsetDateTime happenedAt, BigDecimal amount, Long referenceId, String note) {
        this(userId, happenedAt, amount, null, referenceId, note);
    }
}
