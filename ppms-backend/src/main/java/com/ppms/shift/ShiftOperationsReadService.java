package com.ppms.shift;

import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the operators, reliefs, shares and timeline of a shift for its detail page. */
@Service
@RequiredArgsConstructor
public class ShiftOperationsReadService {

    private static final String SHIFT_OPERATOR_ROLE = "SHIFT_OPERATOR";

    private final ShiftRepository shiftRepository;
    private final ShiftOperatorService shiftOperatorService;
    private final ShiftOperatorRepository shiftOperatorRepository;
    private final ShiftNozzleTurnRepository turnRepository;
    private final ShiftReliefRepository reliefRepository;
    private final ShiftOperatorTotalRepository totalRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final NozzleRepository nozzleRepository;
    private final UserRepository userRepository;

    /** An operator sees only a shift they worked; every other role sees any shift of the pump. */
    @Transactional(readOnly = true)
    public ShiftOperationsResponse operationsOf(Long pumpId, Long shiftId, User currentUser) {
        Shift shift = shiftRepository.findByIdAndPumpId(shiftId, pumpId)
                .filter(found -> currentUser.getRole() != UserRole.OPERATOR || shiftOperatorService.hasWorked(found, currentUser.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        List<ShiftOperator> members = shiftOperatorRepository.findByShiftIdOrderByJoinedAtAscIdAsc(shiftId);
        List<ShiftNozzleTurn> turns = turnRepository.findByShiftIdOrderByStartedAtAscIdAsc(shiftId);
        List<ShiftRelief> reliefs = reliefRepository.findByShiftIdOrderByStartedAtAscIdAsc(shiftId);
        List<ShiftOperatorTotal> totals = totalRepository.findByShiftIdOrderByOperatorIdAsc(shiftId);
        List<Long> nozzleIds = fuelReadingRepository.findByShiftId(shiftId).stream().map(ShiftFuelReading::getNozzleId).toList();
        Map<Long, Nozzle> nozzleById = nozzleRepository.findAllById(nozzleIds).stream()
                .collect(Collectors.toMap(Nozzle::getId, Function.identity()));

        Set<Long> userIds = new HashSet<>();
        userIds.add(shift.getOperatorId());
        members.forEach(member -> userIds.add(member.getOperatorId()));
        reliefs.forEach(relief -> {
            userIds.add(relief.getRelievedOperatorId());
            userIds.add(relief.getReliefOperatorId());
        });
        turns.forEach(turn -> userIds.add(turn.getOperatorId()));
        totals.forEach(total -> userIds.add(total.getOperatorId()));
        if (shift.getBlindCountedByUserId() != null) {
            userIds.add(shift.getBlindCountedByUserId());
        }
        Map<Long, String> nameById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        return new ShiftOperationsResponse(
                operatorsOf(shift, members, turns, reliefs, nozzleIds, nameById),
                reliefs.stream().map(relief -> new ShiftOperationsResponse.ReliefResponse(relief.getId(),
                        relief.getRelievedOperatorId(), nameById.get(relief.getRelievedOperatorId()),
                        relief.getReliefOperatorId(), nameById.get(relief.getReliefOperatorId()), relief.getStartedAt(),
                        relief.getEndedAt(), relief.getCashHandedOver(), relief.getCashHandedBack(), relief.getNote(),
                        relief.isActive())).toList(),
                totals.stream().map(total -> new ShiftOperationsResponse.OperatorShare(total.getOperatorId(),
                        nameById.get(total.getOperatorId()), total.getLitresSold(), total.getAmountDue(),
                        total.getCashCollected(), total.getUpiCollected(), total.getCardCollected(),
                        total.getFleetCardCollected(), total.getCreditTotal(), total.collected(),
                        total.collected() == null ? null : total.collected().subtract(total.getAmountDue()))).toList(),
                ShiftTimeline.of(shift, members, turns, reliefs, nozzleById, nameById));
    }

    private static List<ShiftOperationsResponse.OperatorOnShift> operatorsOf(Shift shift, List<ShiftOperator> members,
                                                                            List<ShiftNozzleTurn> turns, List<ShiftRelief> reliefs,
                                                                            List<Long> nozzleIds, Map<Long, String> nameById) {
        boolean open = shift.getStatus().isOpen();
        Map<Long, Long> holderByNozzle = new HashMap<>();
        if (open) {
            nozzleIds.forEach(nozzleId -> holderByNozzle.put(nozzleId, shift.getOperatorId()));
            turns.stream().filter(ShiftNozzleTurn::isOpen).forEach(turn -> holderByNozzle.put(turn.getNozzleId(), turn.getOperatorId()));
        }
        Set<Long> onBreak = reliefs.stream().filter(ShiftRelief::isActive).map(ShiftRelief::getRelievedOperatorId)
                .collect(Collectors.toSet());

        List<ShiftOperationsResponse.OperatorOnShift> operators = new ArrayList<>();
        operators.add(new ShiftOperationsResponse.OperatorOnShift(shift.getOperatorId(), nameById.get(shift.getOperatorId()),
                SHIFT_OPERATOR_ROLE, shift.getActualStartTime(), shift.getClosedAt(), open,
                onBreak.contains(shift.getOperatorId()), heldBy(shift.getOperatorId(), holderByNozzle)));
        members.stream()
                .sorted(Comparator.comparing(ShiftOperator::getJoinedAt).thenComparing(ShiftOperator::getId))
                .forEach(member -> operators.add(new ShiftOperationsResponse.OperatorOnShift(member.getOperatorId(),
                        nameById.get(member.getOperatorId()), member.getRole().name(), member.getJoinedAt(),
                        member.getLeftAt(), member.isActive(), onBreak.contains(member.getOperatorId()),
                        member.isActive() ? heldBy(member.getOperatorId(), holderByNozzle) : List.of())));
        return operators;
    }

    private static List<Long> heldBy(Long operatorId, Map<Long, Long> holderByNozzle) {
        return holderByNozzle.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getValue(), operatorId))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
