package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDenominations;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * A blind count locks the collections an operator counted before anyone sees the amount the shift should have taken.
 * The close must then use exactly those figures, so the usual discrepancy, tolerance and escalation rules run on the
 * blind count. It is recorded once; a wrong count is corrected by amending the closed shift.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBlindCountService {

    private final ShiftOperatorService shiftOperatorService;
    private final ShiftRepository shiftRepository;
    private final ShiftReadModelService shiftReadModelService;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public ShiftResponse recordBlindCount(Long pumpId, Long shiftId, RecordBlindCountRequest request, User currentUser) {
        Shift shift = shiftOperatorService.lockOpenShift(pumpId, shiftId);
        shiftOperatorService.requireWorkingUnlessManager(shift, currentUser, ErrorCodes.BLIND_COUNT_NOT_OWN_SHIFT,
                "Operators can only count the cash of a shift they are working.");
        if (shift.hasBlindCount()) {
            throw new BusinessException(ErrorCodes.BLIND_COUNT_ALREADY_RECORDED,
                    Map.of("countedAt", shift.getBlindCountedAt().toString()),
                    "The cash of this shift was already counted at " + shift.getBlindCountedAt()
                            + ". Close the shift with that count; an owner can amend it afterwards.");
        }
        CashDenominations.requireMatches(request.cashDenominations(), request.cashCollected());

        shift.setBlindCashCounted(money(request.cashCollected()));
        shift.setBlindCashDenominations(request.cashDenominations());
        shift.setBlindUpiCounted(money(request.upiCollected()));
        shift.setBlindCardCounted(money(request.cardCollected()));
        shift.setBlindFleetCardCounted(money(request.fleetCardCollected()));
        shift.setBlindCountedAt(businessClock.now());
        shift.setBlindCountedByUserId(currentUser.getId());
        shift = shiftRepository.save(shift);

        auditService.log(pumpId, AuditAction.BLIND_CASH_COUNTED, "Shift", shiftId.toString(),
                "Blind count of shift #" + shiftId + ": cash ₹" + shift.getBlindCashCounted() + ", UPI ₹"
                        + shift.getBlindUpiCounted() + ", card ₹" + shift.getBlindCardCounted() + ", fleet card ₹"
                        + shift.getBlindFleetCardCounted(), currentUser);
        log.info("Blind count recorded: shift={}, cash={}, upi={}, card={}, fleetCard={}, by={}", shiftId,
                shift.getBlindCashCounted(), shift.getBlindUpiCounted(), shift.getBlindCardCounted(),
                shift.getBlindFleetCardCounted(), currentUser.getId());
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    /** A pump that counts blind needs the count before the close, and the close must carry the counted figures. */
    void requireCloseMatchesCount(Shift shift, CloseShiftRequest request) {
        if (!shift.hasBlindCount()) {
            boolean countsBlind = pumpLocationRepository.findById(shift.getPumpId())
                    .map(PumpLocation::isBlindCashCount)
                    .orElse(false);
            if (countsBlind) {
                throw new BusinessException(ErrorCodes.BLIND_COUNT_REQUIRED, Map.of(),
                        "Count the cash and other collections first; the expected amount is shown only after the count.");
            }
            return;
        }
        boolean matches = sameMoney(shift.getBlindCashCounted(), request.getCashCollected())
                && sameMoney(shift.getBlindUpiCounted(), request.getUpiCollected())
                && sameMoney(shift.getBlindCardCounted(), request.getCardCollected())
                && sameMoney(shift.getBlindFleetCardCounted(), request.getFleetCardCollected());
        if (!matches) {
            throw new BusinessException(ErrorCodes.BLIND_COUNT_CHANGED_AT_CLOSE,
                    Map.of("cashCounted", shift.getBlindCashCounted().toPlainString(),
                            "upiCounted", shift.getBlindUpiCounted().toPlainString(),
                            "cardCounted", shift.getBlindCardCounted().toPlainString(),
                            "fleetCardCounted", shift.getBlindFleetCardCounted().toPlainString()),
                    "The collections at close must be the ones counted blind: cash ₹" + shift.getBlindCashCounted()
                            + ", UPI ₹" + shift.getBlindUpiCounted() + ", card ₹" + shift.getBlindCardCounted()
                            + ", fleet card ₹" + shift.getBlindFleetCardCounted() + ".");
        }
    }

    private static boolean sameMoney(BigDecimal counted, BigDecimal atClose) {
        return atClose != null && counted.compareTo(money(atClose)) == 0;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
