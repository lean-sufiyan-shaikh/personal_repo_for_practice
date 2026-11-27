package com.ppms.credit;

import com.ppms.common.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Splits each credit account's balance by how old the bills behind it are, by the oldest-bills-first rule of
 * {@link CreditBillLedger}, so the buckets, the days overdue and the credit status agree. Loads the whole pump in a
 * fixed number of queries.
 */
@Service
@RequiredArgsConstructor
public class CreditAgingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final CreditClientRepository clientRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditOverdueService overdueService;
    private final CreditAccountPolicyService accountPolicyService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public CreditAgingResponse agingOfPump(Long pumpId) {
        List<CreditClient> clients = clientRepository.findByPumpIdOrderByNameAsc(pumpId);
        if (clients.isEmpty()) {
            return new CreditAgingResponse(new CreditAgingResponse.Summary(ZERO, ZERO, ZERO, 0, 0, 0), List.of());
        }
        List<Long> clientIds = clients.stream().map(CreditClient::getId).toList();
        Map<Long, Long> holderIdByClientId = clients.stream()
                .collect(Collectors.toMap(CreditClient::getId, accountPolicyService::limitHolderId));
        Map<Long, List<Long>> groupIdsByHolderId = clientIds.stream()
                .collect(Collectors.groupingBy(holderIdByClientId::get));

        LocalDate today = businessClock.today();
        Map<Long, CreditBillLedger.Standing> overdueByHolderId = overdueService.standingByHolderId(clients, today);
        Map<Long, CreditClientStandingResponse> standingById =
                accountPolicyService.creditStandingOfPumpClients(pumpId, clients, overdueByHolderId);
        Map<Long, LocalDate> lastPaymentByHolderId = latestPerHolder(
                paymentRepository.findLastPaymentDatesByClientIds(clientIds), holderIdByClientId);
        List<CreditPayment> pendingPayments = paymentRepository.findPendingApprovalByPumpId(pumpId);

        List<CreditAgingResponse.Row> rows = new ArrayList<>();
        for (CreditClient holder : clients) {
            if (holder.getParentClientId() != null) {
                continue;
            }
            List<Long> groupIds = groupIdsByHolderId.get(holder.getId());
            List<CreditPayment> groupPending = pendingPayments.stream()
                    .filter(payment -> groupIds.contains(payment.getClientId()))
                    .toList();
            rows.add(toRow(holder, groupIds.size() - 1,
                    overdueByHolderId.getOrDefault(holder.getId(), CreditBillLedger.Standing.NOTHING_OWED),
                    lastPaymentByHolderId.get(holder.getId()), standingById.get(holder.getId()), groupPending, today));
        }
        return new CreditAgingResponse(summaryOf(rows), rows);
    }

    private CreditAgingResponse.Row toRow(CreditClient holder, int subAccountCount, CreditBillLedger.Standing overdue,
                                          LocalDate lastPaymentDate, CreditClientStandingResponse standing,
                                          List<CreditPayment> pendingPayments, LocalDate today) {
        BigDecimal[] buckets = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (CreditBillLedger.Bill unpaid : overdue.unpaidBills()) {
            int bucket = bucketOf(unpaid.date(), today);
            buckets[bucket] = buckets[bucket].add(unpaid.amount());
        }

        return new CreditAgingResponse.Row(holder.getId(), holder.getName(), holder.getPhone(), subAccountCount,
                holder.getBillingCycle(), holder.getCreditLimit().setScale(2, RoundingMode.HALF_UP), holder.isUnlimitedCredit(),
                overdue.outstanding(),
                money(buckets[0]), money(buckets[1]), money(buckets[2]), money(buckets[3]),
                overdue.oldestUnpaidSaleDate(), lastPaymentDate, overdue.daysOverdue(), overdue.overdueSince(),
                standing != null ? standing.creditStatus() : CreditStatus.OK,
                standing != null && standing.nearLimit(),
                pendingPayments.size(),
                money(pendingPayments.stream().map(CreditPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    private static int bucketOf(LocalDate date, LocalDate today) {
        long age = ChronoUnit.DAYS.between(date, today);
        if (age <= 30) {
            return 0;
        }
        if (age <= 60) {
            return 1;
        }
        return age <= 90 ? 2 : 3;
    }

    private static Map<Long, LocalDate> latestPerHolder(List<Object[]> rows, Map<Long, Long> holderIdByClientId) {
        Map<Long, LocalDate> latest = new HashMap<>();
        for (Object[] row : rows) {
            latest.merge(holderIdByClientId.get((Long) row[0]), (LocalDate) row[1],
                    (first, second) -> first.isAfter(second) ? first : second);
        }
        return latest;
    }

    private static CreditAgingResponse.Summary summaryOf(List<CreditAgingResponse.Row> rows) {
        BigDecimal total = sum(rows.stream().map(CreditAgingResponse.Row::outstanding).toList());
        BigDecimal overdue = sum(rows.stream().filter(row -> row.daysOverdue() > 0)
                .map(CreditAgingResponse.Row::outstanding).toList());
        BigDecimal overSixty = sum(rows.stream().map(row -> row.days61To90().add(row.over90Days())).toList());
        return new CreditAgingResponse.Summary(total, overdue, overSixty,
                (int) rows.stream().filter(row -> row.daysOverdue() > 0).count(),
                (int) rows.stream().filter(row -> row.creditStatus() == CreditStatus.OVER_LIMIT).count(),
                (int) rows.stream().filter(row -> row.pendingPaymentCount() > 0).count());
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return money(amounts.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
