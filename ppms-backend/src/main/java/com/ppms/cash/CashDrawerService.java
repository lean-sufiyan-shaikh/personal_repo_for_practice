package com.ppms.cash;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Movements of the pump's safe. People record them by hand with a note; a mistaken one is never edited but cancelled
 * by a reversal entry dated on the same day, so the ledger keeps both and the audit log says who did it and why.
 * Every write locks the pump row, so the balance check, the one-opening-balance rule and reversals cannot race.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashDrawerService {

    private static final String ENTITY_NAME = "CashEvent";
    private static final Set<CashEventType> REVERSIBLE_TYPES =
            EnumSet.of(CashEventType.OPENING_BALANCE, CashEventType.CASH_IN, CashEventType.CASH_OUT);

    private final CashEventRepository cashEventRepository;
    private final ClosedDayGuard closedDayGuard;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public CashEvent recordCashEvent(Long pumpId, RecordCashEventRequest req, User currentUser) {
        CashEventType type = req.getEventType();
        if (!type.isRecordedByHand()) {
            throw new BusinessException(ErrorCodes.CASH_EVENT_TYPE_NOT_MANUAL,
                    Map.of("eventTypeCode", type.name()),
                    type + " movements are recorded by confirming a cash drop or recording a bank deposit.");
        }
        if (type == CashEventType.OPENING_BALANCE && !isOwnerOrAdmin(currentUser)) {
            throw new BusinessException(ErrorCodes.CASH_OPENING_BALANCE_OWNER_ONLY, Map.of(),
                    "Only an owner or admin can record the safe's opening balance.");
        }
        closedDayGuard.requireOpen(pumpId, req.getEventDate());
        lockPump(pumpId);
        BigDecimal amount = req.getAmount().setScale(2, RoundingMode.HALF_UP);

        if (type == CashEventType.OPENING_BALANCE
                && cashEventRepository.existsByPumpIdAndEventTypeAndReversedAtIsNull(pumpId, CashEventType.OPENING_BALANCE)) {
            throw new BusinessException(ErrorCodes.CASH_OPENING_BALANCE_EXISTS, Map.of(),
                    "The safe already has an opening balance. Reverse it first if it was wrong.");
        }
        if (type == CashEventType.CASH_OUT) {
            requireBalanceCovers(pumpId, amount);
        }

        CashEvent event = cashEventRepository.save(CashEvent.builder()
                .pumpId(pumpId)
                .eventType(type)
                .amount(amount)
                .description(req.getDescription().trim())
                .eventDate(req.getEventDate())
                .recordedByUserId(currentUser.getId())
                .recordedByHand(true)
                .build());
        auditService.log(pumpId, AuditAction.CASH_EVENT_RECORDED, ENTITY_NAME, event.getId().toString(),
                type + " of ₹" + amount + " on " + event.getEventDate() + ": " + event.getDescription(), currentUser);
        log.info("Cash event recorded: pump={}, event={}, type={}, amount={}, date={}, by={}",
                pumpId, event.getId(), type, amount, event.getEventDate(), currentUser.getId());
        return event;
    }

    /**
     * Cancels a movement typed by hand with an opposite entry on the same day. The reversal is not checked against
     * the balance: it corrects money that was never really there or never really left.
     */
    @Transactional
    public CashEvent reverseCashEvent(Long pumpId, Long eventId, String reason, User currentUser) {
        lockPump(pumpId);
        CashEvent original = cashEventRepository.findById(eventId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CASH_EVENT, "Cash event not found"));
        if (!original.isRecordedByHand() || original.getReversesEventId() != null
                || !REVERSIBLE_TYPES.contains(original.getEventType())) {
            throw new BusinessException(ErrorCodes.CASH_EVENT_NOT_REVERSIBLE,
                    Map.of("eventTypeCode", original.getEventType().name()),
                    "Only an opening balance, cash in or cash out typed by hand can be reversed.");
        }
        if (original.getReversedAt() != null) {
            throw new BusinessException(ErrorCodes.CASH_EVENT_ALREADY_REVERSED, Map.of(),
                    "This cash movement is already reversed.");
        }
        closedDayGuard.requireOpen(pumpId, original.getEventDate());
        CashEvent reversal = saveReversal(original, reason.trim(), currentUser);
        auditService.log(pumpId, AuditAction.CASH_EVENT_REVERSED, ENTITY_NAME, original.getId().toString(),
                "Reversed " + original.getEventType() + " of ₹" + original.getAmount() + " on " + original.getEventDate()
                        + " (reversal #" + reversal.getId() + "): " + reversal.getDescription(), currentUser);
        log.info("Cash event reversed: pump={}, event={}, reversal={}, by={}",
                pumpId, original.getId(), reversal.getId(), currentUser.getId());
        return reversal;
    }

    /** Pays money out of the safe for another record (a supplier payment); the caller audits that record. */
    @Transactional
    public CashEvent recordAutomaticCashOut(Long pumpId, BigDecimal amount, LocalDate eventDate, String description,
                                            User currentUser) {
        lockPump(pumpId);
        requireBalanceCovers(pumpId, amount);
        return cashEventRepository.save(CashEvent.builder()
                .pumpId(pumpId)
                .eventType(CashEventType.CASH_OUT)
                .amount(amount.setScale(2, RoundingMode.HALF_UP))
                .description(description)
                .eventDate(eventDate)
                .recordedByUserId(currentUser.getId())
                .build());
    }

    /** Cancels an automatic movement when the record that made it is voided; the caller audits the void. */
    @Transactional
    public CashEvent reverseAutomaticEvent(Long pumpId, Long eventId, String reason, User currentUser) {
        lockPump(pumpId);
        CashEvent original = cashEventRepository.findById(eventId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CASH_EVENT, "Cash event not found"));
        if (original.getReversedAt() != null) {
            throw new BusinessException(ErrorCodes.CASH_EVENT_ALREADY_REVERSED, Map.of(),
                    "This cash movement is already reversed.");
        }
        return saveReversal(original, reason, currentUser);
    }

    private CashEvent saveReversal(CashEvent original, String reason, User currentUser) {
        original.setReversedAt(businessClock.now());
        cashEventRepository.save(original);
        return cashEventRepository.save(CashEvent.builder()
                .pumpId(original.getPumpId())
                .eventType(original.getEventType() == CashEventType.CASH_OUT ? CashEventType.CASH_IN : CashEventType.CASH_OUT)
                .amount(original.getAmount())
                .description("Reversal of " + original.getEventType() + " #" + original.getId() + ": " + reason)
                .eventDate(original.getEventDate())
                .recordedByUserId(currentUser.getId())
                .reversesEventId(original.getId())
                .build());
    }

    private void requireBalanceCovers(Long pumpId, BigDecimal amount) {
        BigDecimal currentBalance = cashEventRepository.computeBalance(pumpId);
        if (amount.compareTo(currentBalance) > 0) {
            throw new BusinessException(ErrorCodes.CASH_OUT_EXCEEDS_BALANCE,
                    Map.of("amount", amount.toPlainString(), "balance", currentBalance.toPlainString()),
                    "Cash-out amount ₹" + amount + " exceeds the current drawer balance of ₹" + currentBalance + ".");
        }
    }

    private void lockPump(Long pumpId) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
    }

    private static boolean isOwnerOrAdmin(User user) {
        return user.getRole() == UserRole.OWNER || user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SUPER_ADMIN;
    }
}
