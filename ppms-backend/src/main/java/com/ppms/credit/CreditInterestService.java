package com.ppms.credit;

import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 *   - Principal on a day = opening balance + ACTIVE fuel credit sales + credit counter sales up to that day
 *     − APPROVED payments up to that day, each dated by its business date, with the corrections described on
 *     loadDailyPrincipal (credit notes, write-offs, applied deposits, advance refunds, open disputes).
 *   - No interest from the day an account was written off, nor on a closed account.
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
    private static final String RECALCULATED_SOURCE = "RECALCULATED";

    private final CreditClientRepository clientRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditPaymentReversalRepository reversalRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final CreditOpeningBalanceRepository openingBalanceRepository;
    private final CreditAdjustmentRepository adjustmentRepository;
    private final CreditDebitCorrectionsReader correctionsReader;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    // ── Outstanding balance ───────────────────────────────────────────────────

    /**
     * Computes the true outstanding balance for a client (own transactions only):
     *   outstanding = opening balance + sum(fuel credit sales) + sum(credit counter sales) + sum(interest_charges)
     *                 + sum(bounce charges) − sum(approved payments) − credit notes, write-offs and applied deposits
     *                 + advance refunds
     * A result below zero is an advance the client has paid.
     */
    public BigDecimal computeOutstanding(Long clientId) {
        BigDecimal opening      = openingBalanceRepository.sumAmountByClientId(clientId);
        BigDecimal fuelSales    = creditEntryRepository.sumAmountByClientId(clientId);
        BigDecimal counterSales = ancillarySaleRepository.sumAmountByClientIdAndPaymentMode(clientId, CREDIT);
        BigDecimal interest     = interestChargeRepository.sumAmountByClientId(clientId);
        BigDecimal bounce       = reversalRepository.sumBounceChargesByClientId(clientId);
        BigDecimal paid         = paymentRepository.sumAmountByClientId(clientId);
        BigDecimal adjusted     = toAmountMap(adjustmentRepository.signedSumsByClientIds(List.of(clientId)))
                .getOrDefault(clientId, BigDecimal.ZERO);
        return opening.add(fuelSales).add(counterSales).add(interest).add(bounce).subtract(paid).add(adjusted)
                .setScale(2, RoundingMode.HALF_UP);
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
     * Batch version of computeOutstanding — resolves outstanding balance for all clients in 5 DB
     * queries total (one per table), instead of 5 queries per client.
     */
    public Map<Long, BigDecimal> computeOutstandingBatch(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) return Map.of();

        Map<Long, BigDecimal> openingMap      = toAmountMap(openingBalanceRepository.findAmountsByClientIds(clientIds));
        Map<Long, BigDecimal> fuelSalesMap    = toAmountMap(creditEntryRepository.sumAmountsByClientIds(clientIds));
        Map<Long, BigDecimal> counterSalesMap = toAmountMap(ancillarySaleRepository.sumAmountsByClientIdsAndPaymentMode(clientIds, CREDIT));
        Map<Long, BigDecimal> interestMap     = toAmountMap(interestChargeRepository.sumAmountsByClientIds(clientIds));
        Map<Long, BigDecimal> bounceMap       = toAmountMap(reversalRepository.sumBounceChargesByClientIds(clientIds));
        Map<Long, BigDecimal> paymentsMap     = toAmountMap(paymentRepository.sumAmountsByClientIds(clientIds));
        Map<Long, BigDecimal> adjustedMap     = toAmountMap(adjustmentRepository.signedSumsByClientIds(clientIds));

        return clientIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> openingMap.getOrDefault(id, BigDecimal.ZERO)
                        .add(fuelSalesMap.getOrDefault(id, BigDecimal.ZERO))
                        .add(counterSalesMap.getOrDefault(id, BigDecimal.ZERO))
                        .add(interestMap.getOrDefault(id, BigDecimal.ZERO))
                        .add(bounceMap.getOrDefault(id, BigDecimal.ZERO))
                        .subtract(paymentsMap.getOrDefault(id, BigDecimal.ZERO))
                        .add(adjustedMap.getOrDefault(id, BigDecimal.ZERO))
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
     * Batch version — resolves the interest breakdown of the given clients in four queries.
     * Interest is charged on a group's parent, and any account of the group may pay it off, so each group is simulated
     * as one: the charges of all its accounts against the approved payments of all its accounts (reversed payments
     * are not approved). The whole group's breakdown is reported on its parent and a sub-account reports zero.
     * Charges and payments are taken in date order, a charge dated by the last day it covers, so a payment made before
     * a charge's period ended does not count as recovering it; on the same date the charge comes first.
     */
    public Map<Long, InterestBreakdown> computeInterestBreakdownBatch(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) return Map.of();

        List<Long> rootIds = clientRepository.findAllById(clientIds).stream()
                .filter(client -> client.getParentClientId() == null)
                .map(CreditClient::getId)
                .toList();
        if (rootIds.isEmpty()) {
            return clientIds.stream().distinct().collect(Collectors.toMap(id -> id, id -> InterestBreakdown.ZERO));
        }
        Map<Long, Long> rootIdByClientId = new HashMap<>();
        rootIds.forEach(id -> rootIdByClientId.put(id, id));
        clientRepository.findByParentClientIdIn(rootIds)
                .forEach(child -> rootIdByClientId.put(child.getId(), child.getParentClientId()));

        Map<Long, List<CreditInterestCharge>> chargesByRoot = interestChargeRepository
                .findByClientIdInAndVoidedAtIsNull(rootIdByClientId.keySet()).stream()
                .collect(Collectors.groupingBy(charge -> rootIdByClientId.get(charge.getClientId())));
        Map<Long, List<CreditPayment>> paymentsByRoot = chargesByRoot.isEmpty()
                ? Map.of()
                : paymentRepository.findApprovedByClientIdIn(rootIdByClientId.keySet()).stream()
                        .collect(Collectors.groupingBy(payment -> rootIdByClientId.get(payment.getClientId())));

        return clientIds.stream().distinct().collect(Collectors.toMap(
                id -> id,
                id -> chargesByRoot.containsKey(id)
                        ? simulateInterestFirstAllocation(chargesByRoot.get(id), paymentsByRoot.getOrDefault(id, List.of()))
                        : InterestBreakdown.ZERO
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
        charges.forEach(c -> events.add(new Event(c.getPeriodTo(), c.getAmount(), true)));
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
     * Interest actually paid off by the client's group, using interest-first allocation; zero for a sub-account,
     * whose group's interest is reported on its parent.
     */
    public BigDecimal computeTotalInterestRecovered(Long clientId) {
        return computeInterestBreakdownBatch(List.of(clientId)).get(clientId)
                .totalInterestRecovered()
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

    /** Business date of the oldest active credit sale (fuel or counter) or opening balance of the given clients. */
    public Optional<LocalDate> findOldestCreditSaleDate(Collection<Long> clientIds) {
        Optional<LocalDate> oldestFuelSale = creditEntryRepository.findOldestSaleDateByClientIds(clientIds);
        Optional<LocalDate> oldestCounterSale = ancillarySaleRepository.findOldestSaleDateByClientIdsAndPaymentMode(clientIds, CREDIT);
        Optional<LocalDate> oldestOpeningBalance = clientIds.isEmpty() ? Optional.empty()
                : openingBalanceRepository.findByClientIdIn(clientIds).stream()
                        .map(CreditOpeningBalance::getAsOfDate)
                        .min(Comparator.naturalOrder());
        return Stream.of(oldestFuelSale, oldestCounterSale, oldestOpeningBalance)
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
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Client not found: " + clientId));
        if (client.getParentClientId() != null) {
            String parentName = clientRepository.findById(client.getParentClientId())
                    .map(CreditClient::getName)
                    .orElse("the parent account");
            throw new BusinessException(ErrorCodes.CREDIT_INTEREST_ON_SUB_ACCOUNT,
                    Map.of("clientName", client.getName(), "parentName", parentName),
                    "Interest for sub-account '" + client.getName()
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
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Client not found: " + rootClientId));
    }

    private Optional<CreditInterestCharge> applyToClientGroup(CreditClient root, LocalDate periodTo, String source,
                                                              Long appliedByUserId) {
        return applyInterest(root, groupClientIds(root.getId()), periodTo, source, appliedByUserId);
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
        if (root.isClosed() || rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        LocalDate periodFrom = determinePeriodFrom(root, groupClientIds);
        if (periodFrom == null || periodFrom.isAfter(periodTo)) {
            return Optional.empty();
        }

        DailyPrincipal principal = loadDailyPrincipal(groupClientIds);
        Optional<CreditInterestCharge> charge =
                saveChargeIfDue(root, principal, rate, periodFrom, periodTo, source, appliedByUserId);
        charge.ifPresent(saved -> log.info(
                "Interest applied: client={}, groupAccounts={}, amount={}, days={}, rate={}%, period={} to {}, source={}",
                root.getId(), groupClientIds.size(), saved.getAmount(), saved.getDaysApplied(), rate,
                periodFrom, periodTo, source));
        return charge;
    }

    /** Charges the group for the period, or returns empty when the interest comes to less than one paisa. */
    private Optional<CreditInterestCharge> saveChargeIfDue(CreditClient root, DailyPrincipal principal, BigDecimal rate,
                                                           LocalDate periodFrom, LocalDate periodTo, String source,
                                                           Long appliedByUserId) {
        if (root.getWrittenOffOn() != null && !periodTo.isBefore(root.getWrittenOffOn())) {
            periodTo = root.getWrittenOffOn().minusDays(1);
            if (periodTo.isBefore(periodFrom)) {
                return Optional.empty();
            }
        }
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
        if (interest.compareTo(MINIMUM_CHARGE) < 0) {
            log.debug("Client {} interest skipped: calculated amount {} is negligible", root.getId(), interest);
            return Optional.empty();
        }

        long days = ChronoUnit.DAYS.between(periodFrom, periodTo) + 1;
        BigDecimal outstanding = principal.on(periodTo).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return Optional.of(interestChargeRepository.save(CreditInterestCharge.builder()
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
                .build()));
    }

    // ── Recalculation after a backdated change ────────────────────────────────

    /**
     * Re-charges the periods already charged to a client's group when a change dated {@code changeDate}
     * (a backdated or late-approved payment, a voided credit sale) alters the principal of those periods.
     * Every live charge of the group ending on or after that date is waived with {@code reason}, and each of
     * those periods is charged again at the rate it was charged at, so the group owes what it would have
     * owed had the change been on record before the interest was applied.
     *
     * The caller must have written the change already, in the same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculateChargedInterestFrom(Long clientId, LocalDate changeDate, String reason, User actor) {
        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Client not found: " + clientId));
        Long rootId = client.getParentClientId() != null ? client.getParentClientId() : client.getId();
        CreditClient root = lockGroupRoot(rootId);
        List<Long> groupClientIds = groupClientIds(rootId);

        List<CreditInterestCharge> affected = interestChargeRepository.findLiveEndingOnOrAfter(groupClientIds, changeDate);
        if (affected.isEmpty()) {
            return;
        }

        OffsetDateTime waivedAt = businessClock.now();
        BigDecimal waivedTotal = BigDecimal.ZERO;
        for (CreditInterestCharge charge : affected) {
            charge.setVoidedAt(waivedAt);
            charge.setVoidedBy(actor.getId());
            charge.setVoidReason(reason);
            charge.setWaiverKind(InterestWaiverKind.RECALCULATE);
            waivedTotal = waivedTotal.add(charge.getAmount());
        }
        // Waived rows must reach the database before their periods are charged again (unique live period).
        interestChargeRepository.saveAllAndFlush(affected);

        DailyPrincipal principal = loadDailyPrincipal(groupClientIds);
        BigDecimal rechargedTotal = BigDecimal.ZERO;
        LocalDate chargedUntil = null;
        for (CreditInterestCharge waived : affected) {
            LocalDate periodFrom = chargedUntil != null && !waived.getPeriodFrom().isAfter(chargedUntil)
                    ? chargedUntil.plusDays(1)
                    : waived.getPeriodFrom();
            if (periodFrom.isAfter(waived.getPeriodTo())) {
                continue;
            }
            chargedUntil = waived.getPeriodTo();
            rechargedTotal = rechargedTotal.add(saveChargeIfDue(root, principal, waived.getRateApplied(),
                    periodFrom, waived.getPeriodTo(), RECALCULATED_SOURCE, actor.getId())
                    .map(CreditInterestCharge::getAmount)
                    .orElse(BigDecimal.ZERO));
        }

        LocalDate firstDay = affected.get(0).getPeriodFrom();
        LocalDate lastDay = affected.stream().map(CreditInterestCharge::getPeriodTo).max(Comparator.naturalOrder()).orElseThrow();
        auditService.log(root.getPumpId(), AuditAction.INTEREST_RECALCULATED,
                "CreditClient", root.getId().toString(),
                "Interest for '" + root.getName() + "' from " + firstDay + " to " + lastDay + " recalculated: ₹"
                        + waivedTotal.setScale(2, RoundingMode.HALF_UP) + " waived, ₹"
                        + rechargedTotal.setScale(2, RoundingMode.HALF_UP) + " charged again. " + reason,
                actor);
        log.info("Interest recalculated: client={}, period={} to {}, waived={}, recharged={}, by={}",
                root.getId(), firstDay, lastDay, waivedTotal, rechargedTotal, actor.getId());
    }

    /**
     * Charges the days up to the last day interest was ever run for the group that no live charge covers, such as
     * days left uncharged while a debit was disputed and nothing else was owed. Call after recalculating.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void chargeUncoveredDaysThroughLastRun(Long clientId, User actor) {
        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Client not found: " + clientId));
        Long rootId = client.getParentClientId() != null ? client.getParentClientId() : client.getId();
        CreditClient root = lockGroupRoot(rootId);
        List<Long> groupClientIds = groupClientIds(rootId);
        interestChargeRepository.findLastPeriodToOfAnyChargeByClientIds(groupClientIds).ifPresent(lastRun ->
                applyInterest(root, groupClientIds, lastRun, RECALCULATED_SOURCE, actor.getId()));
    }

    /**
     * Re-charges the charged periods of every group a credit sale dated {@code saleDate} was added to, once per group.
     * Nothing changes for a group whose charges all end before the sale date.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recalculateChargedInterestForSales(Collection<Long> clientIds, LocalDate saleDate, String reason,
                                                   User actor) {
        clientRepository.findAllById(clientIds).stream()
                .map(client -> client.getParentClientId() != null ? client.getParentClientId() : client.getId())
                .distinct()
                .sorted()
                .forEach(rootId -> recalculateChargedInterestFrom(rootId, saleDate, reason, actor));
    }

    private List<Long> groupClientIds(Long rootId) {
        List<Long> groupClientIds = new ArrayList<>();
        groupClientIds.add(rootId);
        clientRepository.findByParentClientId(rootId).forEach(child -> groupClientIds.add(child.getId()));
        return groupClientIds;
    }

    private record DailyPrincipal(Map<LocalDate, BigDecimal> changesByDay) {
        BigDecimal on(LocalDate day) {
            return changesByDay.entrySet().stream()
                    .filter(change -> !change.getKey().isAfter(day))
                    .map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * An openly disputed sale bears no interest until the dispute is resolved. Credit notes, write-offs and applied
     * deposits lower the principal from their own date, and an advance refund raises it; a disputed sale credited after
     * the dispute is lowered from the sale's date, so the credited part never bore interest.
     */
    private DailyPrincipal loadDailyPrincipal(Collection<Long> clientIds) {
        CreditDebitCorrections corrections = correctionsReader.of(clientIds);
        Map<LocalDate, BigDecimal> changes = new HashMap<>();
        for (CreditOpeningBalance balance : openingBalanceRepository.findByClientIdIn(clientIds)) {
            changes.merge(balance.getAsOfDate(), balance.getAmount(), BigDecimal::add);
        }
        for (var entry : creditEntryRepository.findActiveByClientIdIn(clientIds)) {
            if (!corrections.isDisputed(CreditDebitType.SALE, entry.getId())) {
                changes.merge(entry.getSaleDate(), entry.getAmount(), BigDecimal::add);
            }
        }
        for (AncillarySale sale : ancillarySaleRepository.findByClientIdsAndPaymentMode(clientIds, CREDIT)) {
            if (!corrections.isDisputed(CreditDebitType.COUNTER_SALE, sale.getId())) {
                changes.merge(sale.getSaleDate(), sale.getTotalWithGst(), BigDecimal::add);
            }
        }
        for (CreditPayment payment : paymentRepository.findApprovedByClientIdIn(clientIds)) {
            changes.merge(payment.getPaidAt(), payment.getAmount().negate(), BigDecimal::add);
        }
        for (CreditAdjustment adjustment : corrections.adjustments()) {
            if (isSaleCredit(adjustment) && corrections.isDisputed(adjustment.getDebitType(), adjustment.getDebitId())) {
                continue;
            }
            if (adjustment.creditsDebit() && !isSaleCredit(adjustment)) {
                continue;
            }
            changes.merge(adjustment.principalDate(), adjustment.signedAmount(), BigDecimal::add);
        }
        return new DailyPrincipal(changes);
    }

    /** Interest and bounce charges are not principal, so a credit note on one leaves the principal alone. */
    private static boolean isSaleCredit(CreditAdjustment adjustment) {
        return adjustment.getDebitType() == CreditDebitType.SALE || adjustment.getDebitType() == CreditDebitType.COUNTER_SALE;
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
