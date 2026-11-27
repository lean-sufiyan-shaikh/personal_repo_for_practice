package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * The operators working a shift: the one it was opened for, and others added for the whole shift (ADDITIONAL) or to
 * cover a break (RELIEF). An operator works one shift at a time; the partial unique index on active shift_operators
 * rows is the final guard when two requests race past the checks here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftOperatorService {

    private static final String ENTITY_NAME = "Shift";
    private static final String ONE_SHIFT_INDEX = "uq_shift_operator_on_one_shift";

    private final ShiftRepository shiftRepository;
    private final ShiftOperatorRepository shiftOperatorRepository;
    private final ShiftNozzleTurnRepository turnRepository;
    private final ShiftReliefRepository reliefRepository;
    private final ShiftLifecycleSupportService lifecycleSupportService;
    private final ShiftReadModelService shiftReadModelService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public ShiftResponse addOperator(Long pumpId, Long shiftId, Long operatorId, User currentUser) {
        Shift shift = lockOpenShift(pumpId, shiftId);
        User operator = lifecycleSupportService.findOperatorForPump(operatorId, pumpId);
        if (isWorking(shift, operatorId)) {
            throw new BusinessException(ErrorCodes.SHIFT_OPERATOR_ALREADY_ON_SHIFT,
                    Map.of("operatorName", operator.getFullName()),
                    operator.getFullName() + " is already working this shift.");
        }
        join(shift, operator, ShiftOperatorRole.ADDITIONAL, currentUser);
        auditService.log(pumpId, AuditAction.SHIFT_OPERATOR_ADDED, ENTITY_NAME, shiftId.toString(),
                operator.getFullName() + " added to shift #" + shiftId, currentUser);
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    /** Only an operator added to the shift can leave it, and only once they hold no nozzle and take part in no relief. */
    @Transactional
    public ShiftResponse removeOperator(Long pumpId, Long shiftId, Long operatorId, User currentUser) {
        Shift shift = lockOpenShift(pumpId, shiftId);
        if (shift.getOperatorId().equals(operatorId)) {
            throw new BusinessException(ErrorCodes.SHIFT_OPERATOR_IS_SHIFT_OWNER, Map.of(),
                    "The shift was opened for this operator, so they cannot leave it. Close or hand over the shift instead.");
        }
        ShiftOperator member = shiftOperatorRepository.findByShiftIdAndOperatorIdAndLeftAtIsNull(shiftId, operatorId)
                .orElseThrow(() -> notOnShift(operatorId));
        rejectWhileInRelief(shift, operatorId);
        boolean holdsNozzle = turnRepository.findByShiftIdAndEndedAtIsNull(shiftId).stream()
                .anyMatch(turn -> turn.getOperatorId().equals(operatorId));
        if (holdsNozzle) {
            throw new BusinessException(ErrorCodes.SHIFT_OPERATOR_HOLDS_NOZZLES, Map.of(),
                    "This operator still works a nozzle. Swap their nozzles to another operator first.");
        }
        member.setLeftAt(businessClock.now());
        shiftOperatorRepository.save(member);
        auditService.log(pumpId, AuditAction.SHIFT_OPERATOR_REMOVED, ENTITY_NAME, shiftId.toString(),
                "Operator #" + operatorId + " left shift #" + shiftId, currentUser);
        log.info("Operator {} left shift {}, by={}", operatorId, shiftId, currentUser.getId());
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    User findOperatorForPump(Long operatorId, Long pumpId) {
        return lifecycleSupportService.findOperatorForPump(operatorId, pumpId);
    }

    /** The shift, locked, after checking it is still open and its day is not closed. */
    Shift lockOpenShift(Long pumpId, Long shiftId) {
        Shift shift = shiftRepository.findByIdAndPumpIdForUpdate(shiftId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        if (!shift.getStatus().isOpen()) {
            throw new BusinessException(ErrorCodes.SHIFT_NOT_OPEN, Map.of("statusCode", shift.getStatus().name()),
                    "Shift is not open. Current status: " + shift.getStatus());
        }
        closedDayGuard.requireOpen(pumpId, shift.getShiftDate());
        return shift;
    }

    /** True for the shift's own operator and for anyone working it now. */
    boolean isWorking(Shift shift, Long userId) {
        return shift.getOperatorId().equals(userId)
                || shiftOperatorRepository.findByShiftIdAndOperatorIdAndLeftAtIsNull(shift.getId(), userId).isPresent();
    }

    /** True for anyone who worked the shift at any time, so they can see it. */
    public boolean hasWorked(Shift shift, Long userId) {
        return shift.getOperatorId().equals(userId) || shiftOperatorRepository.existsByShiftIdAndOperatorId(shift.getId(), userId);
    }

    /** An OPERATOR may act only on a shift they are working; every other role may act on any shift of the pump. */
    void requireWorkingUnlessManager(Shift shift, User currentUser, String errorCode, String message) {
        if (currentUser.getRole() == UserRole.OPERATOR && !isWorking(shift, currentUser.getId())) {
            throw new BusinessException(errorCode, Map.of(), message);
        }
    }

    /** Adds an operator who is working no other shift. */
    ShiftOperator join(Shift shift, User operator, ShiftOperatorRole role, User currentUser) {
        rejectOperatorOnAnotherShift(operator);
        lifecycleSupportService.rejectNightShiftWithoutConsent(Boolean.TRUE.equals(shift.getIsNightShift()), operator);
        try {
            ShiftOperator saved = shiftOperatorRepository.saveAndFlush(ShiftOperator.builder()
                    .shiftId(shift.getId())
                    .operatorId(operator.getId())
                    .role(role)
                    .joinedAt(businessClock.now())
                    .addedByUserId(currentUser.getId())
                    .build());
            log.info("Operator {} joined shift {} as {}, by={}", operator.getId(), shift.getId(), role, currentUser.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (!cause.contains(ONE_SHIFT_INDEX)) {
                throw e;
            }
            log.warn("Concurrent join refused for operator {}: {}", operator.getId(), cause);
            throw new BusinessException(ErrorCodes.OPERATOR_SHIFT_OPENED_CONCURRENTLY,
                    Map.of("operatorName", operator.getFullName()),
                    operator.getFullName() + " was just given another shift. Refresh the page to see it.");
        }
    }

    /** Refuses an operator who has their own open shift or is working someone else's. */
    void rejectOperatorOnAnotherShift(User operator) {
        Optional<Long> shiftId = shiftRepository.findOpenShiftByOperator(operator.getId()).map(Shift::getId)
                .or(() -> shiftOperatorRepository.findByOperatorIdAndLeftAtIsNull(operator.getId()).map(ShiftOperator::getShiftId));
        shiftId.ifPresent(existing -> {
            throw new BusinessException(ErrorCodes.OPERATOR_SHIFT_ALREADY_OPEN,
                    Map.of("operatorName", operator.getFullName(), "shiftId", existing),
                    operator.getFullName() + " already has an open shift (ID " + existing + ").");
        });
    }

    void rejectWhileInRelief(Shift shift, Long operatorId) {
        boolean inRelief = reliefRepository.findByShiftIdAndEndedAtIsNull(shift.getId()).stream()
                .anyMatch(relief -> relief.getRelievedOperatorId().equals(operatorId) || relief.getReliefOperatorId().equals(operatorId));
        if (inRelief) {
            throw new BusinessException(ErrorCodes.SHIFT_OPERATOR_IN_RELIEF, Map.of(),
                    "This operator is on a break or covering one. End the relief first.");
        }
    }

    /** Everyone still on the shift leaves it when it closes. */
    void releaseAllAtClose(Long shiftId, OffsetDateTime closedAt) {
        shiftOperatorRepository.releaseAllOfShift(shiftId, closedAt);
    }

    static BusinessException notOnShift(Long operatorId) {
        return new BusinessException(ErrorCodes.SHIFT_OPERATOR_NOT_ON_SHIFT, Map.of("operatorId", operatorId),
                "Operator #" + operatorId + " is not working this shift.");
    }
}
