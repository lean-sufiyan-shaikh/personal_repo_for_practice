package com.ppms.pettycash;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * Writes the petty cash box's movements. Every write locks the pump row, so a draw cannot take the box below zero
 * when two expenses are approved at the same moment. The caller audits the record behind the movement.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PettyCashLedger {

    private final PettyCashEntryRepository entryRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final BusinessClock businessClock;

    @Transactional(propagation = Propagation.MANDATORY)
    public PettyCashEntry drawForExpense(Long pumpId, Long expenseId, BigDecimal amount, LocalDate date,
                                         String description, User currentUser) {
        lockPump(pumpId);
        BigDecimal balance = balanceOf(pumpId);
        if (amount.compareTo(balance) > 0) {
            throw new BusinessException(ErrorCodes.PETTY_CASH_BALANCE_TOO_LOW,
                    Map.of("amount", amount.toPlainString(), "balance", balance.toPlainString()),
                    "Petty cash holds ₹" + balance + ", less than the ₹" + amount + " this expense needs. Top it up first.");
        }
        PettyCashEntry entry = entryRepository.save(PettyCashEntry.builder()
                .pumpId(pumpId)
                .entryType(PettyCashEntryType.EXPENSE)
                .amount(amount.setScale(2, RoundingMode.HALF_UP).negate())
                .entryDate(date)
                .description(description)
                .expenseId(expenseId)
                .recordedByUserId(currentUser.getId())
                .build());
        log.info("Petty cash drawn: pump={}, entry={}, expense={}, amount={}", pumpId, entry.getId(), expenseId, amount);
        return entry;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PettyCashEntry reverseExpenseDraw(Long pumpId, Long expenseId, String reason, User currentUser) {
        lockPump(pumpId);
        PettyCashEntry draw = entryRepository.findByExpenseIdAndEntryType(expenseId, PettyCashEntryType.EXPENSE)
                .filter(found -> found.getPumpId().equals(pumpId) && found.getReversedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.EXPENSE,
                        "No petty cash draw to reverse for expense " + expenseId));
        draw.setReversedAt(businessClock.now());
        entryRepository.save(draw);
        PettyCashEntry reversal = entryRepository.save(PettyCashEntry.builder()
                .pumpId(pumpId)
                .entryType(PettyCashEntryType.REVERSAL)
                .amount(draw.getAmount().negate())
                .entryDate(draw.getEntryDate())
                .description("Reversal of expense #" + expenseId + ": " + reason)
                .expenseId(expenseId)
                .reversesEntryId(draw.getId())
                .recordedByUserId(currentUser.getId())
                .build());
        log.info("Petty cash draw reversed: pump={}, entry={}, reversal={}, expense={}", pumpId, draw.getId(),
                reversal.getId(), expenseId);
        return reversal;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PettyCashEntry recordTopUp(Long pumpId, BigDecimal amount, LocalDate date, String description,
                                      Long safeCashEventId, User currentUser) {
        lockPump(pumpId);
        return entryRepository.save(PettyCashEntry.builder()
                .pumpId(pumpId)
                .entryType(PettyCashEntryType.TOP_UP)
                .amount(amount)
                .entryDate(date)
                .description(description)
                .safeCashEventId(safeCashEventId)
                .recordedByUserId(currentUser.getId())
                .build());
    }

    public BigDecimal balanceOf(Long pumpId) {
        return entryRepository.computeBalance(pumpId).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal balanceAtEndOf(Long pumpId, LocalDate date) {
        return entryRepository.computeBalanceAtEndOf(pumpId, date).setScale(2, RoundingMode.HALF_UP);
    }

    private void lockPump(Long pumpId) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
    }
}
