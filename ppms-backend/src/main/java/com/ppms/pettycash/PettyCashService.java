package com.ppms.pettycash;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.common.time.DateRangeBounds;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The petty cash box: topped up from the safe towards its float, drawn by expenses paid from petty cash, and counted.
 * A count beyond the box's tolerance notifies the owner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PettyCashService {

    private static final String ENTITY_NAME = "PettyCash";

    private final PettyCashBoxRepository boxRepository;
    private final PettyCashEntryRepository entryRepository;
    private final PettyCashCountRepository countRepository;
    private final PettyCashLedger ledger;
    private final CashDrawerService cashDrawerService;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final ClosedDayGuard closedDayGuard;
    private final NotificationDedupService dedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public PettyCashResponses.Summary summary(Long pumpId) {
        PettyCashBox box = boxOf(pumpId);
        BigDecimal balance = ledger.balanceOf(pumpId);
        PettyCashResponses.Count latest = countRepository.findFirstByPumpIdOrderByCountForDateDescCountedAtDescIdDesc(pumpId)
                .map(count -> toResponse(count, nameOf(count.getCountedByUserId())))
                .orElse(null);
        return new PettyCashResponses.Summary(box.getFloatAmount(), box.getCountTolerance(), balance,
                box.getFloatAmount().subtract(balance).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP), latest);
    }

    @Transactional
    public PettyCashResponses.Summary updateSettings(Long pumpId, PettyCashRequests.UpdateSettings request, User currentUser) {
        PettyCashBox box = boxOf(pumpId);
        BigDecimal oldFloat = box.getFloatAmount();
        box.setFloatAmount(request.floatAmount().setScale(2, RoundingMode.HALF_UP));
        box.setCountTolerance(request.countTolerance().setScale(2, RoundingMode.HALF_UP));
        box.setUpdatedByUserId(currentUser.getId());
        box.setUpdatedAt(businessClock.now());
        boxRepository.save(box);
        auditService.log(pumpId, AuditAction.PETTY_CASH_SETTINGS_UPDATED, ENTITY_NAME, pumpId.toString(),
                "Petty cash float set to ₹" + box.getFloatAmount() + " (was ₹" + oldFloat + "), count tolerance ₹"
                        + box.getCountTolerance(), currentUser);
        log.info("Petty cash settings updated: pump={}, float={}, tolerance={}, by={}", pumpId, box.getFloatAmount(),
                box.getCountTolerance(), currentUser.getId());
        return summary(pumpId);
    }

    /** Takes the cash out of the safe and puts it in the box in one transaction, so neither happens alone. */
    @Transactional
    public PettyCashResponses.Entry topUp(Long pumpId, PettyCashRequests.TopUp request, User currentUser) {
        LocalDate date = dateOrToday(request.entryDate());
        closedDayGuard.requireOpen(pumpId, date);
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().trim();
        String description = "Petty cash top-up" + (notes != null ? ": " + notes : "");
        CashEvent safeCashOut = cashDrawerService.recordAutomaticCashOut(pumpId, amount, date, description, currentUser);
        PettyCashEntry entry = ledger.recordTopUp(pumpId, amount, date, description, safeCashOut.getId(), currentUser);
        auditService.log(pumpId, AuditAction.PETTY_CASH_TOPPED_UP, ENTITY_NAME, entry.getId().toString(),
                "Petty cash topped up with ₹" + amount + " from the safe on " + date
                        + " (safe cash-out #" + safeCashOut.getId() + ")" + (notes != null ? ": " + notes : ""), currentUser);
        log.info("Petty cash topped up: pump={}, entry={}, safeEvent={}, amount={}, date={}, by={}", pumpId, entry.getId(),
                safeCashOut.getId(), amount, date, currentUser.getId());
        return toEntry(entry, ledger.balanceOf(pumpId));
    }

    /** The pump row is locked so the expected balance cannot move between reading it and saving the count. */
    @Transactional
    public PettyCashResponses.Count recordCount(Long pumpId, PettyCashRequests.Count request, User currentUser) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        LocalDate date = dateOrToday(request.countForDate());
        closedDayGuard.requireOpen(pumpId, date);
        PettyCashBox box = boxOf(pumpId);
        BigDecimal counted = request.countedAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal expected = date.isBefore(businessClock.today()) ? ledger.balanceAtEndOf(pumpId, date) : ledger.balanceOf(pumpId);
        PettyCashCount count = countRepository.save(PettyCashCount.builder()
                .pumpId(pumpId)
                .countForDate(date)
                .countedAmount(counted)
                .expectedAmount(expected)
                .varianceAmount(counted.subtract(expected))
                .toleranceAmount(box.getCountTolerance())
                .notes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim())
                .countedByUserId(currentUser.getId())
                .countedAt(businessClock.now())
                .build());
        auditService.log(pumpId, AuditAction.PETTY_CASH_COUNTED, ENTITY_NAME, count.getId().toString(),
                "Petty cash counted for " + date + ": ₹" + counted + " against ₹" + expected + " expected (difference ₹"
                        + count.getVarianceAmount() + ")", currentUser);
        if (count.isBeyondTolerance()) {
            notifyVariance(count, currentUser);
        }
        log.info("Petty cash counted: pump={}, count={}, for={}, counted={}, expected={}, by={}", pumpId, count.getId(),
                date, counted, expected, currentUser.getId());
        return toResponse(count, currentUser.getFullName());
    }

    @Transactional(readOnly = true)
    public PagedResponse<PettyCashResponses.Entry> ledger(Long pumpId, LocalDate from, LocalDate to, int page, int size) {
        LocalDate fromDate = DateRangeBounds.startOrEarliest(from);
        LocalDate toDate = DateRangeBounds.endOrLatest(to);
        DateRangeBounds.requireStartNotAfterEnd(fromDate, toDate);
        Page<PettyCashEntryRepository.LedgerBalance> balances =
                entryRepository.findLedgerBalances(pumpId, fromDate, toDate, PageRequests.of(page, size));
        Map<Long, PettyCashEntry> entryById = entryRepository
                .findAllById(balances.getContent().stream().map(PettyCashEntryRepository.LedgerBalance::getId).toList())
                .stream().collect(Collectors.toMap(PettyCashEntry::getId, Function.identity()));
        return PagedResponse.of(balances.map(balance -> toEntry(entryById.get(balance.getId()), balance.getBalanceAfter())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PettyCashResponses.Count> counts(Long pumpId, int page, int size) {
        Page<PettyCashCount> counts = countRepository.findByPumpIdOrderByCountForDateDescCountedAtDescIdDesc(pumpId,
                PageRequests.of(page, size));
        Set<Long> userIds = counts.getContent().stream().map(PettyCashCount::getCountedByUserId).collect(Collectors.toSet());
        Map<Long, String> nameById = userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return PagedResponse.of(counts.map(count -> toResponse(count, nameById.getOrDefault(count.getCountedByUserId(), "Unknown"))));
    }

    private LocalDate dateOrToday(LocalDate date) {
        LocalDate today = businessClock.today();
        if (date == null) {
            return today;
        }
        if (date.isAfter(today)) {
            throw new BusinessException(ErrorCodes.PETTY_CASH_DATE_IN_FUTURE, Map.of("date", date.toString()),
                    "Petty cash cannot be moved or counted on a future date.");
        }
        return date;
    }

    private void notifyVariance(PettyCashCount count, User countedBy) {
        String amount = count.getVarianceAmount().abs().toPlainString();
        String direction = count.getVarianceAmount().signum() < 0 ? "SHORT" : "OVER";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("varianceTypeCode", direction);
        params.put("amount", amount);
        params.put("countedAmount", count.getCountedAmount().toPlainString());
        params.put("expectedAmount", count.getExpectedAmount().toPlainString());
        params.put("countedByName", countedBy.getFullName());
        dedupService.maybeInsert(count.getPumpId(), NotificationType.PETTY_CASH_COUNT_VARIANCE,
                "PETTY_CASH_COUNT_VARIANCE:count:" + count.getId(),
                "Petty Cash " + (direction.equals("SHORT") ? "Short" : "Over"),
                countedBy.getFullName() + " counted ₹" + count.getCountedAmount().toPlainString()
                        + " in petty cash against ₹" + count.getExpectedAmount().toPlainString() + " expected: "
                        + direction + " by ₹" + amount + ".",
                params);
    }

    private PettyCashBox boxOf(Long pumpId) {
        return boxRepository.findById(pumpId).orElseGet(() -> PettyCashBox.defaultsFor(pumpId));
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(User::getFullName).orElse("Unknown");
    }

    private static PettyCashResponses.Entry toEntry(PettyCashEntry entry, BigDecimal balanceAfter) {
        return new PettyCashResponses.Entry(entry.getId(), entry.getEntryType(), entry.getAmount(),
                balanceAfter.setScale(2, RoundingMode.HALF_UP), entry.getDescription(), entry.getEntryDate(),
                entry.getExpenseId(), entry.getReversedAt() != null, entry.getCreatedAt());
    }

    private static PettyCashResponses.Count toResponse(PettyCashCount count, String countedByName) {
        return new PettyCashResponses.Count(count.getId(), count.getCountForDate(), count.getCountedAmount(),
                count.getExpectedAmount(), count.getVarianceAmount(), count.getToleranceAmount(),
                count.isBeyondTolerance(), count.getNotes(), countedByName, count.getCountedAt());
    }
}
