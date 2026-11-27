package com.ppms.shift;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Who worked a shift and how its nozzles changed hands: the operators with the nozzles they work now, every relief,
 * each operator's share once the shift is closed, and a timeline of what happened, oldest first.
 */
public record ShiftOperationsResponse(
        List<OperatorOnShift> operators,
        List<ReliefResponse> reliefs,
        List<OperatorShare> operatorShares,
        List<TimelineEvent> timeline
) {

    /** role is SHIFT_OPERATOR for the one the shift was opened for, else ADDITIONAL or RELIEF. */
    public record OperatorOnShift(Long operatorId, String operatorName, String role, OffsetDateTime joinedAt,
                                  OffsetDateTime leftAt, boolean active, boolean onBreak, List<Long> nozzleIds) {}

    public record ReliefResponse(Long id, Long relievedOperatorId, String relievedOperatorName, Long reliefOperatorId,
                                 String reliefOperatorName, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                 BigDecimal cashHandedOver, BigDecimal cashHandedBack, String note, boolean active) {}

    /** The collection figures and difference are null when the close did not split the collections by operator. */
    public record OperatorShare(Long operatorId, String operatorName, BigDecimal litresSold, BigDecimal amountDue,
                                BigDecimal cashCollected, BigDecimal upiCollected, BigDecimal cardCollected,
                                BigDecimal fleetCardCollected, BigDecimal creditTotal, BigDecimal collected,
                                BigDecimal difference) {}

    /**
     * type is SHIFT_OPENED, OPERATOR_JOINED, OPERATOR_LEFT, NOZZLE_SWAPPED, RELIEF_STARTED, RELIEF_ENDED,
     * BLIND_COUNTED or SHIFT_CLOSED. operatorName is who it is about; otherOperatorName the second person, if any.
     */
    public record TimelineEvent(OffsetDateTime at, String type, String operatorName, String otherOperatorName,
                                String role, List<NozzleReading> readings, BigDecimal amount, String note) {}

    public record NozzleReading(Long nozzleId, Integer nozzleNumber, BigDecimal meterReading) {}
}
