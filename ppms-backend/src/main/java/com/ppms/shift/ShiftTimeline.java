package com.ppms.shift;

import com.ppms.pump.Nozzle;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The events of a shift in the order they happened, from its rows of operators, nozzle turns and reliefs. */
final class ShiftTimeline {

    private ShiftTimeline() {
    }

    static List<ShiftOperationsResponse.TimelineEvent> of(Shift shift, List<ShiftOperator> members, List<ShiftNozzleTurn> turns,
                                                         List<ShiftRelief> reliefs, Map<Long, Nozzle> nozzleById,
                                                         Map<Long, String> nameById) {
        List<ShiftOperationsResponse.TimelineEvent> events = new ArrayList<>();
        events.add(event(shift.getActualStartTime(), "SHIFT_OPENED", nameById.get(shift.getOperatorId()), null, null,
                List.of(), null, null));
        for (ShiftOperator member : members) {
            if (member.getRole() != ShiftOperatorRole.ADDITIONAL) {
                continue;
            }
            String name = nameById.get(member.getOperatorId());
            events.add(event(member.getJoinedAt(), "OPERATOR_JOINED", name, null, member.getRole().name(), List.of(), null, null));
            if (member.getLeftAt() != null && !member.getLeftAt().equals(shift.getClosedAt())) {
                events.add(event(member.getLeftAt(), "OPERATOR_LEFT", name, null, member.getRole().name(), List.of(), null, null));
            }
        }
        for (ShiftNozzleTurn turn : turns) {
            if (turn.getStartReason() == NozzleTurnStartReason.SWAP) {
                Long fromOperatorId = previousTurn(turns, turn).map(ShiftNozzleTurn::getOperatorId).orElse(shift.getOperatorId());
                events.add(event(turn.getStartedAt(), "NOZZLE_SWAPPED", nameById.get(fromOperatorId),
                        nameById.get(turn.getOperatorId()), null, List.of(readingOf(turn, nozzleById)), null, turn.getNote()));
            }
        }
        for (ShiftRelief relief : reliefs) {
            String relieved = nameById.get(relief.getRelievedOperatorId());
            String reliever = nameById.get(relief.getReliefOperatorId());
            List<ShiftOperationsResponse.NozzleReading> startReadings = turns.stream()
                    .filter(turn -> relief.getId().equals(turn.getReliefId()))
                    .map(turn -> readingOf(turn, nozzleById))
                    .toList();
            events.add(event(relief.getStartedAt(), "RELIEF_STARTED", relieved, reliever, null, startReadings,
                    relief.getCashHandedOver(), relief.getNote()));
            if (relief.getEndedAt() != null) {
                List<ShiftOperationsResponse.NozzleReading> endReadings = turns.stream()
                        .filter(turn -> relief.getId().equals(turn.getReliefId()) && turn.getEndReading() != null)
                        .map(turn -> new ShiftOperationsResponse.NozzleReading(turn.getNozzleId(),
                                nozzleNumberOf(turn, nozzleById), turn.getEndReading()))
                        .toList();
                events.add(event(relief.getEndedAt(), "RELIEF_ENDED", relieved, reliever, null, endReadings,
                        relief.getCashHandedBack(), null));
            }
        }
        if (shift.getBlindCountedAt() != null) {
            events.add(event(shift.getBlindCountedAt(), "BLIND_COUNTED", nameById.get(shift.getBlindCountedByUserId()),
                    null, null, List.of(), null, null));
        }
        if (shift.getClosedAt() != null) {
            events.add(event(shift.getClosedAt(), "SHIFT_CLOSED", nameById.get(shift.getOperatorId()), null, null,
                    List.of(), null, null));
        }
        events.sort(Comparator.comparing(ShiftOperationsResponse.TimelineEvent::at));
        return events;
    }

    private static Optional<ShiftNozzleTurn> previousTurn(List<ShiftNozzleTurn> turns, ShiftNozzleTurn turn) {
        ShiftNozzleTurn previous = null;
        for (ShiftNozzleTurn candidate : turns) {
            if (candidate == turn) {
                break;
            }
            if (Objects.equals(candidate.getNozzleId(), turn.getNozzleId())) {
                previous = candidate;
            }
        }
        return Optional.ofNullable(previous);
    }

    private static ShiftOperationsResponse.NozzleReading readingOf(ShiftNozzleTurn turn, Map<Long, Nozzle> nozzleById) {
        return new ShiftOperationsResponse.NozzleReading(turn.getNozzleId(), nozzleNumberOf(turn, nozzleById), turn.getStartReading());
    }

    private static Integer nozzleNumberOf(ShiftNozzleTurn turn, Map<Long, Nozzle> nozzleById) {
        Nozzle nozzle = nozzleById.get(turn.getNozzleId());
        return nozzle == null ? null : nozzle.getNozzleNumber();
    }

    private static ShiftOperationsResponse.TimelineEvent event(OffsetDateTime at, String type, String operatorName,
                                                               String otherOperatorName, String role,
                                                               List<ShiftOperationsResponse.NozzleReading> readings,
                                                               BigDecimal amount, String note) {
        return new ShiftOperationsResponse.TimelineEvent(at, type, operatorName, otherOperatorName, role, readings, amount, note);
    }
}
