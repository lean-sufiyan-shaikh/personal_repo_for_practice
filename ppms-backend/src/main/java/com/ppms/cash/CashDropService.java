package com.ppms.cash;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Two-person cash drops: the operator (or a manager for them) records cash moved from the bag into the safe during
 * an open shift, and someone else confirms it arrived or rejects it. Confirming adds a CASH_DROP movement to the
 * safe's ledger, and only confirmed drops lower the cash expected in the bag at close.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashDropService {

    private static final String ENTITY_NAME = "CashDrop";

    private final CashDropRepository dropRepository;
    private final CashEventRepository cashEventRepository;
    private final ShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final NotificationDedupService dedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final ClosedDayGuard closedDayGuard;

    @Transactional
    public CashDropResponse recordDrop(Long pumpId, Long shiftId, RecordCashDropRequest request, User currentUser) {
        Shift shift = shiftRepository.findByIdAndPumpIdForUpdate(shiftId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        if (currentUser.getRole() == UserRole.OPERATOR && !currentUser.getId().equals(shift.getOperatorId())) {
            throw new BusinessException(ErrorCodes.CASH_DROP_NOT_OWN_SHIFT, Map.of(),
                    "Operators can only drop cash from their own open shift.");
        }
        if (!shift.getStatus().isOpen()) {
            throw new BusinessException(ErrorCodes.CASH_DROP_SHIFT_NOT_OPEN,
                    Map.of("statusCode", shift.getStatus().name()),
                    "Cash can only be dropped from an open shift. Current status: " + shift.getStatus());
        }
        closedDayGuard.requireOpen(pumpId, shift.getShiftDate());
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        CashDenominations.requireMatches(request.denominations(), amount);

        CashDrop drop = dropRepository.save(CashDrop.builder()
                .pumpId(pumpId)
                .shiftId(shiftId)
                .operatorId(shift.getOperatorId())
                .amount(amount)
                .denominations(request.denominations())
                .recordedByUserId(currentUser.getId())
                .build());

        String operatorName = userRepository.findById(shift.getOperatorId()).map(User::getFullName).orElse("Unknown");
        auditService.log(pumpId, AuditAction.CASH_DROP_RECORDED, ENTITY_NAME, drop.getId().toString(),
                "Cash drop of ₹" + amount + " recorded from shift #" + shiftId + " (" + operatorName + "), waiting for confirmation",
                currentUser);
        notifyPending(drop, operatorName);
        log.info("Cash drop recorded: pump={}, shift={}, drop={}, amount={}, by={}",
                pumpId, shiftId, drop.getId(), amount, currentUser.getId());
        return toResponses(List.of(drop)).get(0);
    }

    /** The shift row is locked first, in the same order as a close, so a confirm and a close never interleave. */
    @Transactional
    public CashDropResponse confirmDrop(Long pumpId, Long dropId, User currentUser) {
        LockedDrop locked = findPendingDropLockingShift(pumpId, dropId);
        CashDrop drop = locked.drop();
        if (currentUser.getId().equals(drop.getRecordedByUserId())) {
            throw new BusinessException(ErrorCodes.CASH_DROP_SELF_CONFIRMATION, Map.of(),
                    "A cash drop must be confirmed by someone other than the person who recorded it.");
        }
        CashEvent event = cashEventRepository.save(CashEvent.builder()
                .pumpId(pumpId)
                .eventType(CashEventType.CASH_DROP)
                .amount(drop.getAmount())
                .description("Cash drop from shift #" + drop.getShiftId() + " (drop #" + drop.getId() + ")")
                .eventDate(locked.shift().getShiftDate())
                .shiftId(drop.getShiftId())
                .recordedByUserId(currentUser.getId())
                .build());
        drop.setStatus(CashDropStatus.CONFIRMED);
        drop.setCashEventId(event.getId());
        markReviewed(drop, currentUser);
        dropRepository.save(drop);

        auditService.log(pumpId, AuditAction.CASH_DROP_CONFIRMED, ENTITY_NAME, drop.getId().toString(),
                "Cash drop of ₹" + drop.getAmount() + " from shift #" + drop.getShiftId() + " confirmed in the safe",
                currentUser);
        log.info("Cash drop confirmed: pump={}, drop={}, amount={}, by={}", pumpId, dropId, drop.getAmount(), currentUser.getId());
        return toResponses(List.of(drop)).get(0);
    }

    @Transactional
    public CashDropResponse rejectDrop(Long pumpId, Long dropId, RejectCashDropRequest request, User currentUser) {
        CashDrop drop = findPendingDropLockingShift(pumpId, dropId).drop();
        drop.setStatus(CashDropStatus.REJECTED);
        drop.setRejectionReason(request.reason().trim());
        markReviewed(drop, currentUser);
        dropRepository.save(drop);

        auditService.log(pumpId, AuditAction.CASH_DROP_REJECTED, ENTITY_NAME, drop.getId().toString(),
                "Cash drop of ₹" + drop.getAmount() + " from shift #" + drop.getShiftId() + " rejected: " + drop.getRejectionReason(),
                currentUser);
        log.info("Cash drop rejected: pump={}, drop={}, by={}", pumpId, dropId, currentUser.getId());
        return toResponses(List.of(drop)).get(0);
    }

    /** Every drop of the shift, newest first; an operator only sees drops of their own shift. */
    @Transactional(readOnly = true)
    public List<CashDropResponse> dropsForShift(Long pumpId, Long shiftId, User currentUser) {
        Shift shift = shiftRepository.findById(shiftId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .filter(found -> currentUser.getRole() != UserRole.OPERATOR || currentUser.getId().equals(found.getOperatorId()))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        return toResponses(dropRepository.findByShiftIdOrderByCreatedAtDescIdDesc(shift.getId()));
    }

    @Transactional(readOnly = true)
    public List<CashDropResponse> pendingDrops(Long pumpId) {
        return toResponses(dropRepository.findByPumpIdAndStatusOrderByCreatedAtAscIdAsc(pumpId, CashDropStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public CashDropRepository.DropTotal pendingTotal(Long pumpId) {
        return dropRepository.totalForPump(pumpId, CashDropStatus.PENDING);
    }

    /**
     * Called by the shift close while it holds the shift lock: refuses the close while any drop still waits for a
     * decision, and returns the confirmed total that is already in the safe.
     */
    @Transactional(readOnly = true)
    public BigDecimal confirmedTotalForClose(Long shiftId) {
        CashDropRepository.DropTotal pending = dropRepository.totalForShift(shiftId, CashDropStatus.PENDING);
        if (pending.getDropCount() > 0) {
            String amount = pending.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString();
            throw new BusinessException(ErrorCodes.CASH_DROPS_PENDING_AT_CLOSE,
                    Map.of("count", pending.getDropCount(), "amount", amount),
                    pending.getDropCount() + " cash drop(s) worth ₹" + amount + " from this shift are waiting for "
                            + "confirmation. Confirm or reject them before closing the shift.");
        }
        return dropRepository.totalForShift(shiftId, CashDropStatus.CONFIRMED).getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    private record LockedDrop(CashDrop drop, Shift shift) {}

    /** A drop belongs to its shift's business day, so a closed day refuses its confirmation and rejection too. */
    private LockedDrop findPendingDropLockingShift(Long pumpId, Long dropId) {
        Long shiftId = dropRepository.findShiftIdOfDrop(dropId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CASH_DROP, "Cash drop not found"));
        Shift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        CashDrop drop = dropRepository.findByIdAndPumpIdForUpdate(dropId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CASH_DROP, "Cash drop not found"));
        if (drop.getStatus() != CashDropStatus.PENDING) {
            throw new BusinessException(ErrorCodes.CASH_DROP_NOT_PENDING,
                    Map.of("statusCode", drop.getStatus().name()),
                    "This cash drop is already " + drop.getStatus() + ".");
        }
        closedDayGuard.requireOpen(pumpId, shift.getShiftDate());
        return new LockedDrop(drop, shift);
    }

    private void markReviewed(CashDrop drop, User currentUser) {
        drop.setReviewedByUserId(currentUser.getId());
        drop.setReviewedAt(businessClock.now());
    }

    private void notifyPending(CashDrop drop, String operatorName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operatorName", operatorName);
        params.put("amount", drop.getAmount().toPlainString());
        params.put("shiftId", drop.getShiftId());
        dedupService.maybeInsert(drop.getPumpId(), NotificationType.CASH_DROP_PENDING,
                "CASH_DROP_PENDING:drop:" + drop.getId(),
                "Cash Drop to Confirm",
                operatorName + " dropped ₹" + drop.getAmount().toPlainString() + " into the safe from shift #"
                        + drop.getShiftId() + ". Count it and confirm or reject it.",
                params);
    }

    private List<CashDropResponse> toResponses(List<CashDrop> drops) {
        Collection<Long> userIds = drops.stream()
                .flatMap(drop -> Stream.of(drop.getOperatorId(), drop.getRecordedByUserId(), drop.getReviewedByUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> nameById = userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (first, second) -> first));
        Function<Long, String> nameOf = id -> id == null ? null : nameById.getOrDefault(id, "Unknown");
        return drops.stream()
                .map(drop -> new CashDropResponse(drop.getId(), drop.getShiftId(), drop.getOperatorId(),
                        nameOf.apply(drop.getOperatorId()), drop.getAmount(), drop.getDenominations(), drop.getStatus(),
                        nameOf.apply(drop.getRecordedByUserId()), drop.getCreatedAt(),
                        nameOf.apply(drop.getReviewedByUserId()), drop.getReviewedAt(), drop.getRejectionReason()))
                .toList();
    }
}
