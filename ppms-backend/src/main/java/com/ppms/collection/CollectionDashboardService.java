package com.ppms.collection;

import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditAgingResponse;
import com.ppms.credit.CreditAgingService;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.credit.CreditStatus;
import com.ppms.pump.PumpLocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Reads the whole pump in a fixed number of queries: the aging view, this month's payments, promises and reminders. */
@Service
@RequiredArgsConstructor
public class CollectionDashboardService {

    private static final int TOP_OVERDUE_COUNT = 5;
    private static final int MISSED_PROMISE_DAYS = 30;
    private static final String UNKNOWN_STAFF_NAME = "Unknown";

    private final CreditAgingService agingService;
    private final CreditFollowUpService followUpService;
    private final CreditPaymentPromiseRepository promiseRepository;
    private final CreditPaymentRepository paymentRepository;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public CollectionDashboardResponse dashboardOf(Long pumpId) {
        PumpLocation pump = followUpService.requirePump(pumpId);
        LocalDate today = businessClock.today();
        CreditAgingResponse aging = agingService.agingOfPump(pumpId);
        List<CreditAgingResponse.Row> accounts = aging.clients();
        Map<Long, String> nameById = accounts.stream()
                .collect(Collectors.toMap(CreditAgingResponse.Row::clientId, CreditAgingResponse.Row::name));

        List<CreditPaymentPromise> dueToday = promiseRepository.findByPumpIdAndStatus(pumpId, PromiseStatus.OPEN).stream()
                .filter(promise -> promise.getPromisedDate().equals(today))
                .toList();
        List<CreditPaymentPromise> missed = promiseRepository
                .findTop20ByPumpIdAndStatusAndPromisedDateGreaterThanEqualOrderByPromisedDateDesc(
                        pumpId, PromiseStatus.MISSED, today.minusDays(MISSED_PROMISE_DAYS));
        Set<Long> userIds = new HashSet<>();
        Stream.concat(dueToday.stream(), missed.stream()).forEach(promise -> userIds.add(promise.getRecordedByUserId()));
        Map<Long, String> userNameById = followUpService.userNames(userIds);
        Function<CreditPaymentPromise, PromiseResponse> toResponse = promise -> PromiseResponse.of(promise,
                nameById.get(promise.getClientId()), userNameById.getOrDefault(promise.getRecordedByUserId(), UNKNOWN_STAFF_NAME));

        List<CreditAgingResponse.Row> overdue = accounts.stream().filter(row -> row.daysOverdue() > 0).toList();
        return new CollectionDashboardResponse(
                aging.summary().totalOutstanding(),
                aging.summary().overdueAmount(),
                aging.summary().overdueCount(),
                ageBuckets(accounts),
                paymentRepository.sumApprovedByPumpIdBetween(pumpId, today.withDayOfMonth(1), today)
                        .setScale(2, RoundingMode.HALF_UP),
                dueToday.stream().map(toResponse).toList(),
                missed.stream().map(toResponse).toList(),
                accounts.stream().filter(row -> row.creditStatus() == CreditStatus.ON_HOLD)
                        .sorted(Comparator.comparingLong(CreditAgingResponse.Row::daysOverdue).reversed())
                        .map(CollectionDashboardService::figureOf)
                        .toList(),
                overdue.stream()
                        .sorted(Comparator.comparing(CreditAgingResponse.Row::outstanding).reversed())
                        .limit(TOP_OVERDUE_COUNT)
                        .map(CollectionDashboardService::figureOf)
                        .toList(),
                followUpService.dueReminders(pump, accounts));
    }

    private static CollectionDashboardResponse.AgeBuckets ageBuckets(List<CreditAgingResponse.Row> accounts) {
        return new CollectionDashboardResponse.AgeBuckets(
                sum(accounts, CreditAgingResponse.Row::upTo30Days),
                sum(accounts, CreditAgingResponse.Row::days31To60),
                sum(accounts, CreditAgingResponse.Row::days61To90),
                sum(accounts, CreditAgingResponse.Row::over90Days));
    }

    private static BigDecimal sum(List<CreditAgingResponse.Row> accounts, Function<CreditAgingResponse.Row, BigDecimal> amount) {
        return accounts.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static CollectionDashboardResponse.AccountFigure figureOf(CreditAgingResponse.Row account) {
        return new CollectionDashboardResponse.AccountFigure(account.clientId(), account.name(), account.outstanding(),
                account.daysOverdue());
    }
}
