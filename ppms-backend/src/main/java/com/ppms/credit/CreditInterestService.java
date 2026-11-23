package com.ppms.credit;

import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.transaction.TransactionPaymentMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles all interest calculation and application logic for credit clients.
 *
 * Interest model:
 *   - Simple interest on principal only: earlier interest charges are never charged interest.
 *   - Principal on a day = ACTIVE fuel credit sales + credit counter sales up to that day
 *     − APPROVED payments up to that day, each dated by its business date.
 *   - Each day of the period is charged on that day's principal:
 *     amount = Σ principal(day) × (monthly rate / 100) / 30
 *   - A parent with sub-accounts is charged on the combined balance of the whole group, using the
 *     parent's rate and grace days; the charge is booked on the parent and sub-accounts are never charged.
 *   - Grace period: interest does not start until X days after the first credit sale
 *   - period_from is always the day after the last applied period ended (or first eligible date)
 *   - period_to is today's date (for manual) or end of previous month (for scheduled)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditInterestService {

    private static final BigDecimal DAYS_IN_MONTH = new BigDecimal("30");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal MINIMUM_CHARGE = new BigDecimal("0.01");
    private static final TransactionPaymentMode CREDIT = TransactionPaymentMode.CREDIT;

    private final CreditClientRepository clientRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final CreditPaymentRepository paymentRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final BusinessClock businessClock;

    // ── Outstanding balance ───────────────────────────────────────────────────

    /**
     * Computes the true outstanding balance for a client (own transactions only):
     *   outstanding = sum(fuel credit sales) + sum(credit counter sales) + sum(interest_charges) − sum(payments)
     */
    public BigDecimal computeOutstanding(Long clientId) {
        BigDecimal fuelSales    = creditEntryRepository.sumAmountByClientId(clientId);
        BigDecimal counterSales = ancillarySaleRepository.sumAmountByClientIdAndPaymentMode(clientId, CREDIT);
        BigDecimal interest     = interestChargeRepository.sumAmountByClientId(clientId);
        BigDecimal paid         = paymentRepository.sumAmountByClientId(clientId);
        return fuelSales.add(counterSales).add(interest).subtract(paid).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Computes the combined outstanding balance for a parent account including all sub-accounts.
     * Used for displaying the group liability on the Credit page and for credit limit enforcement.
     */
    public BigDecimal computeOutstandingWithChildren(Long clientId) {
        BigDecimal own = computeOutstanding(clientId);
        List<CreditClient> children = clientRepository.findByParentClientId(clientId);
        if (children.isEmpty()) {
            return own;
        }
        List<Long> childIds = children.stream().map(CreditClient::getId).toList();
        Map<Long, BigDecimal> childBalances = computeOutstandingBatch(childIds);
        BigDecimal childrenSum = childBalances.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return own.add(childrenSum).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Batch version of computeOutstanding — resolves outstanding balance for all clients in 4 DB
     * queries total (one per table), instead of 4 queries per client.
     */
    public Map<Long, BigDecimal> computeOutstandingBatch(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) return Map.of();

        Map<Long, BigDecimal> fuelSalesMap    = toAmountMap(creditEntryRepository.sumAmountsByClientIds(clientIds));
        Map<Long, BigDecimal> counterSalesMap = toAmountMap(ancillarySaleRepository.sumAmountsByClientIdsAndPaymentMode(clientIds, CREDIT));
        Map<Long, BigDecimal> interestMap     = toAmountMap(interestChargeRepository.sumAmountsByClientIds(clientIds));
        Map<Long, BigDecimal> paymentsMap     = toAmountMap(paymentRepository.sumAmountsByClientIds(clientIds));

        return clientIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> fuelSalesMap.getOrDefault(id, BigDecimal.ZERO)
                        .add(counterSalesMap.getOrDefault(id, BigDecimal.ZERO))
                        .add(interestMap.getOrDefault(id, BigDecimal.ZERO))
                        .subtract(paymentsMap.getOrDefault(id, BigDecimal.ZERO))
                        .setScale(2, RoundingMode.HALF_UP)
        ));
    }

    /**
     * Holds the interest breakdown for a single client account:
     *   outstandingInterest  — portion of the outstanding balance that is unpaid interest
     *   totalInterestRecovered — total interest covered by payments (interest-first allocation)
     */
    public record InterestBreakdown(BigDecimal outstandingInterest, BigDecimal totalInterestRecovered) {
        public static final InterestBreakdown ZERO =
            new InterestBreakdown(BigDecimal.ZERO, BigDecimal.ZERO);

        public InterestBreakdown add(InterestBreakdown other) {
            return new InterestBreakdown(
                this.outstandingInterest.add(other.outstandingInterest),
                this.totalInterestRecovered.add(other.totalInterestRecovered)
            );
        }
    }

    /**
     * Batch version — resolves interest breakdown for all clients in 2 DB queries total.
     * Uses chronological simulation: processes interest charges and payments in date order
     * so that a payment made BEFORE an interest charge is NOT counted as recovering that interest.
     * On the same date, interest charges are processed before payments.
     */
    public Map<Long, InterestBreakdown> computeInterestBreakdownBatch(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) return Map.of();

        List<CreditInterestCharge> allCharges = interestChargeRepository.findByClientIdInAndVoidedAtIsNull(clientIds);
        Map<Long, List<CreditInterestCharge>> chargesByClient = allCharges.stream()
                .collect(Collectors.groupingBy(CreditInterestCharge::getClientId));
        List<CreditPayment> allPayments = chargesByClient.isEmpty()
                ? List.of()
                : paymentRepository.findApprovedByClientIdIn(chargesByClient.keySet());
        Map<Long, List<CreditPayment>> paymentsByClient = allPayments.stream()
                .collect(Collectors.groupingBy(CreditPayment::getClientId));

        return clientIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> simulateInterestFirstAllocation(
                        chargesByClient.getOrDefault(id, List.of()),
                        paymentsByClient.getOrDefault(id, List.of())
                )
        ));
    }

    /**
     * Simulates interest-first payment allocation in chronological order.
     * Each interest charge adds to an "interest bucket"; each payment drains it first.
     * Only the portion of payments that overlaps with an existing interest bucket counts as recovered.
     */
    private InterestBreakdown simulateInterestFirstAllocation(
            List<CreditInterestCharge> charges,
            List<CreditPayment> payments) {

        if (charges.isEmpty()) return InterestBreakdown.ZERO;

        record Event(LocalDate date, BigDecimal amount, boolean isInterest) {}

        List<Event> events = new ArrayList<>();
        charges.forEach(c -> events.add(new Event(
                c.getCreatedAt().atZoneSameInstant(businessClock.zone()).toLocalDate(), c.getAmount(), true)));
        payments.forEach(p -> events.add(new Event(p.getPaidAt(), p.getAmount(), false)));

        // Same-date: interest charges before payments so they can be immediately drained
        events.sort(Comparator.comparing(Event::date).thenComparing(e -> e.isInterest() ? 0 : 1));

        BigDecimal interestBucket = BigDecimal.ZERO;
        BigDecimal recovered = BigDecimal.ZERO;

        for (Event event : events) {
            if (event.isInterest()) {
                interestBucket = interestBucket.add(event.amount());
            } else {
                BigDecimal interestPaid = event.amount().min(interestBucket);
                interestBucket = interestBucket.subtract(interestPaid);
                recovered = recovered.add(interestPaid);
            }
        }

        return new InterestBreakdown(
                interestBucket.setScale(2, RoundingMode.HALF_UP),
                recovered.setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Interest actually paid off by the client and its sub-accounts, using interest-first allocation.
     */
    public BigDecimal computeTotalInterestRecovered(Long clientId) {
        List<Long> groupIds = new ArrayList<>();
        groupIds.add(clientId);
        clientRepository.findByParentClientId(clientId).forEach(child -> groupIds.add(child.getId()));
        return computeInterestBreakdownBatch(groupIds).values().stream()
                .map(InterestBreakdown::totalInterestRecovered)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Converts JPQL GROUP BY result rows [clientId, sumAmount] into a clientId → amount map. */
    private static Map<Long, BigDecimal> toAmountMap(List<Object[]> rows) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) {
            Long id = (Long) row[0];
            BigDecimal amount = row[1] instanceof BigDecimal bd ? bd : new BigDecimal(row[1].toString());
            map.put(id, amount);
        }
        return map;
    }

    /** Business date of the oldest active credit sale (fuel or counter) of the given clients. */
    public Optional<LocalDate> findOldestCreditSaleDate(Collection<Long> clientIds) {
        Optional<LocalDate> oldestFuelSale = creditEntryRepository.findOldestSaleDateByClientIds(clientIds);
        Optional<LocalDate> oldestCounterSale = ancillarySaleRepository.findOldestSaleDateByClientIdsAndPaymentMode(clientIds, CREDIT);
        return Stream.of(oldestFuelSale, oldestCounterSale)
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder());
    }

    // ── Manual application (triggered by owner/admin via API) ──────────────────

    /**
     * Applies pro-rata simple interest for a client up to today. For a parent, the charge covers the
     * combined balance of the parent and its sub-accounts. A sub-account cannot be charged on its own.
     */
    @Transactional
    public Optional<CreditInterestCharge> applyProRata(Long pumpId, Long clientId, Long appliedByUserId) {
        CreditClient client = clientRepository.findById(clientId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException("Client not found: " + clientId));
        if (client.getParentClientId() != null) {
            String parentName = clientRepository.findById(client.getParentClientId())
                    .map(CreditClient::getName)
                    .orElse("the parent account");
            throw new BusinessException("Interest for sub-account '" + client.getName()
                    + "' is charged on its parent account '" + parentName + "'. Apply interest on the parent instead.");
        }
        CreditClient root = lockGroupRoot(client.getId());
        return applyToClientGroup(root, businessClock.today(), "MANUAL", appliedByUserId);
    }

    /**
     * Applies interest to one root client's group in a transaction of its own,
     * so a failure here cannot roll back charges already made for other client groups.
     *
     * @return number of charges created (0 or 1)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int applyToClientGroupInNewTransaction(Long rootClientId, LocalDate periodTo, String source, Long appliedByUserId) {
        CreditClient root = lockGroupRoot(rootClientId);
        return applyToClientGroup(root, periodTo, source, appliedByUserId).isPresent() ? 1 : 0;
    }

    /**
     * Serialises interest runs for one client group, so a manual run and the scheduled job cannot
     * both read the same last charge and charge the same days twice.
     */
    private CreditClient lockGroupRoot(Long rootClientId) {
        return clientRepository.findByIdForUpdate(rootClientId)
                .orElseThrow(() -> new ResourceNotFoundException("Client not found: " + rootClientId));
    }

    private Optional<CreditInterestCharge> applyToClientGroup(CreditClient root, LocalDate periodTo, String source,
                                                              Long appliedByUserId) {
        List<Long> groupClientIds = new ArrayList<>();
        groupClientIds.add(root.getId());
        clientRepository.findByParentClientId(root.getId()).forEach(child -> groupClientIds.add(child.getId()));
        return applyInterest(root, groupClientIds, periodTo, source, appliedByUserId);
    }

    // ── Core calculation ──────────────────────────────────────────────────────

    /**
     * Core interest application logic shared by manual and scheduled triggers.
     *
     * The principal is the combined balance of every account in {@code groupClientIds}; the charge is
     * booked on {@code root} using its rate and grace days.
     *
     * @param root            the standalone client or group parent that is charged
     * @param groupClientIds  the root and all of its sub-accounts
     * @param periodTo        the last date (inclusive) to cover — today for manual, yesterday for scheduled
     * @param source          "MANUAL" or "MONTHLY_SCHEDULED"
     * @param appliedByUserId the user ID triggering the charge (null for scheduled)
     * @return the saved charge, or empty if the group was not eligible
     */
    private Optional<CreditInterestCharge> applyInterest(CreditClient root, List<Long> groupClientIds,
                                                         LocalDate periodTo, String source, Long appliedByUserId) {

        BigDecimal rate = root.getMonthlyInterestRate();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        LocalDate periodFrom = determinePeriodFrom(root, groupClientIds);
        if (periodFrom == null || periodFrom.isAfter(periodTo)) {
            return Optional.empty();
        }

        DailyPrincipal principal = loadDailyPrincipal(groupClientIds);
        BigDecimal dailyRate = rate.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP)
                .divide(DAYS_IN_MONTH, 10, RoundingMode.HALF_UP);
        BigDecimal interest = BigDecimal.ZERO;
        for (LocalDate day = periodFrom; !day.isAfter(periodTo); day = day.plusDays(1)) {
            BigDecimal owed = principal.on(day);
            if (owed.signum() > 0) {
                interest = interest.add(owed.multiply(dailyRate));
            }
        }
        interest = interest.setScale(2, RoundingMode.HALF_UP);
        long days = ChronoUnit.DAYS.between(periodFrom, periodTo) + 1;
        BigDecimal outstanding = principal.on(periodTo).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        if (interest.compareTo(MINIMUM_CHARGE) < 0) {
            log.debug("Client {} interest skipped: calculated amount {} is negligible", root.getId(), interest);
            return Optional.empty();
        }

        CreditInterestCharge charge = CreditInterestCharge.builder()
                .pumpId(root.getPumpId())
                .clientId(root.getId())
                .amount(interest)
                .outstandingBalance(outstanding)
                .rateApplied(rate)
                .daysApplied((int) days)
                .periodFrom(periodFrom)
                .periodTo(periodTo)
                .source(source)
                .appliedByUserId(appliedByUserId)
                .build();

        charge = interestChargeRepository.save(charge);

        log.info("Interest applied: client={}, groupAccounts={}, amount={}, days={}, rate={}%, period={} to {}, source={}",
                root.getId(), groupClientIds.size(), interest, days, rate, periodFrom, periodTo, source);

        return Optional.of(charge);
    }

    private record DailyPrincipal(Map<LocalDate, BigDecimal> changesByDay) {
        BigDecimal on(LocalDate day) {
            return changesByDay.entrySet().stream()
                    .filter(change -> !change.getKey().isAfter(day))
                    .map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private DailyPrincipal loadDailyPrincipal(Collection<Long> clientIds) {
        Map<LocalDate, BigDecimal> changes = new HashMap<>();
        for (var entry : creditEntryRepository.findActiveByClientIdIn(clientIds)) {
            changes.merge(entry.getSaleDate(), entry.getAmount(), BigDecimal::add);
        }
        for (AncillarySale sale : ancillarySaleRepository.findByClientIdsAndPaymentMode(clientIds, CREDIT)) {
            changes.merge(sale.getSaleDate(), sale.getTotalWithGst(), BigDecimal::add);
        }
        for (CreditPayment payment : paymentRepository.findApprovedByClientIdIn(clientIds)) {
            changes.merge(payment.getPaidAt(), payment.getAmount().negate(), BigDecimal::add);
        }
        return new DailyPrincipal(changes);
    }

    /**
     * Calculates the start date of the next chargeable period for a client group.
     *
     * Logic:
     * - If any account of the group was charged before: period_from = latest period_to + 1 day,
     *   so days already charged to a sub-account under the earlier per-account rule are not charged again
     * - Otherwise: period_from = oldest credit sale date of the group + the root's grace days
     *
     * Returns null if the group has no credit sales at all.
     */
    private LocalDate determinePeriodFrom(CreditClient root, Collection<Long> groupClientIds) {
        Optional<LocalDate> lastPeriodTo = interestChargeRepository.findLastPeriodToByClientIds(groupClientIds);
        if (lastPeriodTo.isPresent()) {
            return lastPeriodTo.get().plusDays(1);
        }

        Optional<LocalDate> oldestSale = findOldestCreditSaleDate(groupClientIds);
        if (oldestSale.isEmpty()) {
            return null;
        }

        int graceDays = root.getInterestGraceDays() != null ? root.getInterestGraceDays() : 1;
        return oldestSale.get().plusDays(graceDays);
    }
}
