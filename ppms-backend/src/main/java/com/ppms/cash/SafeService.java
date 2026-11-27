package com.ppms.cash;

import com.ppms.audit.AuditService;
import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.common.time.DateRangeBounds;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.shift.DiscrepancyType;
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
 * The pump safe, whose ledger is the pump's cash events: shift cash, confirmed drops, recoveries and counter sales
 * come in; bank deposits and manual cash-outs (petty cash, expenses paid in cash) go out. A count compares the
 * cash in the safe with that ledger and notifies the owner when the difference is beyond the pump's tolerance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SafeService {

    private final CashEventRepository cashEventRepository;
    private final SafeCountRepository safeCountRepository;
    private final CashDropService cashDropService;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final NotificationDedupService dedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final ClosedDayGuard closedDayGuard;
    private final SafeCountDateCheck safeCountDateCheck;

    @Transactional(readOnly = true)
    public SafeSummaryResponse summary(Long pumpId) {
        PumpLocation pump = findPump(pumpId);
        CashDropRepository.DropTotal pending = cashDropService.pendingTotal(pumpId);
        SafeCountResponse latestCount = safeCountRepository.findFirstByPumpIdOrderByCountedAtDescIdDesc(pumpId)
                .map(count -> toResponse(count, nameOf(count.getCountedByUserId())))
                .orElse(null);
        return new SafeSummaryResponse(balanceOf(pumpId), pending.getDropCount(),
                pending.getAmount().setScale(2, RoundingMode.HALF_UP), pump.getSafeCountTolerance(), latestCount,
                pump.getSafeCountCutoffTime(), safeCountDateCheck.isYesterdayOpen(pump));
    }

    /** What a count for that business day is compared with: the balance at its end, or right now for today. */
    @Transactional(readOnly = true)
    public SafeDayBalanceResponse expectedBalance(Long pumpId, LocalDate businessDate) {
        findPump(pumpId);
        return new SafeDayBalanceResponse(businessDate, expectedBalanceFor(pumpId, businessDate));
    }

    @Transactional(readOnly = true)
    public PagedResponse<SafeLedgerEntryResponse> ledger(Long pumpId, LocalDate from, LocalDate to, int page, int size) {
        LocalDate fromDate = DateRangeBounds.startOrEarliest(from);
        LocalDate toDate = DateRangeBounds.endOrLatest(to);
        DateRangeBounds.requireStartNotAfterEnd(fromDate, toDate);
        Page<CashEventRepository.LedgerBalance> balances =
                cashEventRepository.findLedgerBalances(pumpId, fromDate, toDate, PageRequests.of(page, size));
        Map<Long, CashEvent> eventById = cashEventRepository
                .findAllById(balances.getContent().stream().map(CashEventRepository.LedgerBalance::getId).toList())
                .stream().collect(Collectors.toMap(CashEvent::getId, Function.identity()));
        return PagedResponse.of(balances.map(balance -> toLedgerEntry(eventById.get(balance.getId()), balance.getBalanceAfter())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<SafeCountResponse> counts(Long pumpId, int page, int size) {
        Page<SafeCount> counts = safeCountRepository.findByPumpIdOrderByCountedAtDescIdDesc(pumpId, PageRequests.of(page, size));
        Set<Long> userIds = counts.getContent().stream().map(SafeCount::getCountedByUserId).collect(Collectors.toSet());
        Map<Long, String> nameById = userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return PagedResponse.of(counts.map(count -> toResponse(count, nameById.getOrDefault(count.getCountedByUserId(), "Unknown"))));
    }

    /**
     * The pump row is locked so the expected balance cannot move between reading it and saving the count. A count for
     * an earlier business day is compared with the ledger balance at the end of that day, not with today's.
     */
    @Transactional
    public SafeCountResponse recordCount(Long pumpId, RecordSafeCountRequest request, User currentUser) {
        PumpLocation pump = pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        LocalDate countForDate = request.countForDate() != null ? request.countForDate() : businessClock.today();
        SafeCountTiming timing = safeCountDateCheck.timingOf(pump, countForDate, request.backDateReason(), currentUser);
        closedDayGuard.requireOpen(pumpId, countForDate);
        BigDecimal counted = request.countedAmount().setScale(2, RoundingMode.HALF_UP);
        CashDenominations.requireMatches(request.denominations(), counted);

        BigDecimal expected = expectedBalanceFor(pumpId, countForDate);
        BigDecimal variance = counted.subtract(expected);
        SafeCount count = safeCountRepository.save(SafeCount.builder()
                .pumpId(pumpId)
                .countedAmount(counted)
                .expectedAmount(expected)
                .varianceAmount(variance)
                .varianceType(variance.signum() < 0 ? DiscrepancyType.SHORT : variance.signum() > 0 ? DiscrepancyType.OVER : null)
                .toleranceAmount(pump.getSafeCountTolerance())
                .denominations(request.denominations())
                .notes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim())
                .countedByUserId(currentUser.getId())
                .countedAt(businessClock.now())
                .countForDate(countForDate)
                .late(timing != SafeCountTiming.ON_THE_DAY)
                .backDateReason(timing == SafeCountTiming.BACK_DATED ? request.backDateReason().trim() : null)
                .build());

        auditService.log(pumpId, timing.auditAction(), "SafeCount", count.getId().toString(),
                auditText(count, timing), currentUser);
        if (isBeyondTolerance(count)) {
            notifyVariance(count, currentUser);
        }
        log.info("Safe counted: pump={}, count={}, for={}, timing={}, counted={}, expected={}, variance={}, by={}",
                pumpId, count.getId(), countForDate, timing, counted, expected, variance, currentUser.getId());
        return toResponse(count, currentUser.getFullName());
    }

    BigDecimal balanceOf(Long pumpId) {
        return cashEventRepository.computeBalance(pumpId).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal expectedBalanceFor(Long pumpId, LocalDate businessDate) {
        return businessDate.isBefore(businessClock.today())
                ? cashEventRepository.computeBalanceAtEndOf(pumpId, businessDate).setScale(2, RoundingMode.HALF_UP)
                : balanceOf(pumpId);
    }

    private static String auditText(SafeCount count, SafeCountTiming timing) {
        String figures = "₹" + count.getCountedAmount() + " against ₹" + count.getExpectedAmount() + " expected ("
                + describeVariance(count) + ")";
        return switch (timing) {
            case ON_THE_DAY -> "Safe counted: " + figures;
            case NEXT_MORNING -> "Safe counted late for " + count.getCountForDate() + ": " + figures;
            case BACK_DATED -> "Safe count back-dated to " + count.getCountForDate() + ": " + figures
                    + ". Reason: " + count.getBackDateReason();
        };
    }

    private void notifyVariance(SafeCount count, User countedBy) {
        String amount = count.getVarianceAmount().abs().toPlainString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("varianceTypeCode", count.getVarianceType().name());
        params.put("amount", amount);
        params.put("countedAmount", count.getCountedAmount().toPlainString());
        params.put("expectedAmount", count.getExpectedAmount().toPlainString());
        params.put("countedByName", countedBy.getFullName());
        dedupService.maybeInsert(count.getPumpId(), NotificationType.SAFE_COUNT_VARIANCE,
                "SAFE_COUNT_VARIANCE:count:" + count.getId(),
                "Safe Count " + (count.getVarianceType() == DiscrepancyType.SHORT ? "Short" : "Over"),
                countedBy.getFullName() + " counted ₹" + count.getCountedAmount().toPlainString() + " in the safe against ₹"
                        + count.getExpectedAmount().toPlainString() + " expected: " + count.getVarianceType() + " by ₹" + amount + ".",
                params);
    }

    private static boolean isBeyondTolerance(SafeCount count) {
        return count.getVarianceAmount().abs().compareTo(count.getToleranceAmount()) > 0;
    }

    private static String describeVariance(SafeCount count) {
        return count.getVarianceType() == null ? "matches" : count.getVarianceType() + " by ₹" + count.getVarianceAmount().abs();
    }

    private static SafeLedgerEntryResponse toLedgerEntry(CashEvent event, BigDecimal balanceAfter) {
        boolean goesOut = event.getEventType() == CashEventType.CASH_OUT || event.getEventType() == CashEventType.BANK_DEPOSIT;
        return new SafeLedgerEntryResponse(event.getId(), event.getEventType(),
                goesOut ? event.getAmount().negate() : event.getAmount(), balanceAfter.setScale(2, RoundingMode.HALF_UP),
                event.getDescription(), event.getEventDate(), event.getCreatedAt());
    }

    private static SafeCountResponse toResponse(SafeCount count, String countedByName) {
        return new SafeCountResponse(count.getId(), count.getCountedAmount(), count.getExpectedAmount(), count.getVarianceAmount(),
                count.getVarianceType(), count.getToleranceAmount(), isBeyondTolerance(count), count.getDenominations(),
                count.getNotes(), countedByName, count.getCountedAt(), count.getCountForDate(), count.isLate(),
                count.getBackDateReason());
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(User::getFullName).orElse("Unknown");
    }

    private PumpLocation findPump(Long pumpId) {
        return pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
    }
}
