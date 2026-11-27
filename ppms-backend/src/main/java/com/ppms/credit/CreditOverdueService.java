package com.ppms.credit;

import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.transaction.TransactionPaymentMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies the oldest-bills-first rule of {@link CreditBillLedger} to credit accounts. Every caller that needs to know
 * whether an account is overdue reads it here, so the credit status, the hold, the aging and the collections agree.
 * A sub-account's bills and payments count towards its parent's account, judged by the parent's billing cycle.
 * An opening balance is a bill like a credit sale, dated the day the pump went live.
 * Credit notes, write-offs, applied deposits, advance refunds and open disputes are taken into account as described
 * on {@link AccountBills}; a written-off account is never overdue. Loads any number of accounts in a fixed number of queries.
 */
@Service
@RequiredArgsConstructor
public class CreditOverdueService {

    private final CreditClientRepository clientRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditPaymentReversalRepository reversalRepository;
    private final CreditOpeningBalanceRepository openingBalanceRepository;
    private final CreditDebitCorrectionsReader correctionsReader;

    /** The standing of each account among the given clients, by the id of the client that holds the account. */
    @Transactional(readOnly = true)
    public Map<Long, CreditBillLedger.Standing> standingByHolderId(Collection<CreditClient> clients, LocalDate today) {
        if (clients.isEmpty()) {
            return Map.of();
        }
        Map<Long, CreditClient> clientById = clients.stream()
                .collect(Collectors.toMap(CreditClient::getId, Function.identity(), (first, second) -> first));
        Map<Long, Long> holderIdByClientId = new HashMap<>();
        clientById.values().forEach(client -> holderIdByClientId.put(client.getId(),
                client.getParentClientId() != null ? client.getParentClientId() : client.getId()));
        Collection<Long> clientIds = clientById.keySet();

        CreditDebitCorrections corrections = correctionsReader.of(clientIds);
        AccountBills accountBills = new AccountBills(holderIdByClientId, corrections);
        openingBalanceRepository.findByClientIdIn(clientIds).forEach(balance ->
                accountBills.addUncorrectable(balance.getClientId(), balance.getAsOfDate(), balance.getAmount()));
        creditEntryRepository.findActiveByClientIdIn(clientIds).forEach(entry -> accountBills.add(entry.getClientId(),
                CreditDebitType.SALE, entry.getId(), entry.getSaleDate(), entry.getAmount(), true));
        ancillarySaleRepository.findByClientIdsAndPaymentMode(clientIds, TransactionPaymentMode.CREDIT).forEach(sale ->
                accountBills.add(sale.getClientId(), CreditDebitType.COUNTER_SALE, sale.getId(), sale.getSaleDate(),
                        sale.getTotalWithGst(), true));
        interestChargeRepository.findByClientIdInAndVoidedAtIsNull(clientIds).forEach(charge ->
                accountBills.add(charge.getClientId(), CreditDebitType.INTEREST, charge.getId(), charge.getPeriodTo(),
                        charge.getAmount(), false));
        reversalRepository.findWithBounceChargeByClientIdIn(clientIds).forEach(reversal ->
                accountBills.add(reversal.getClientId(), CreditDebitType.BOUNCE_CHARGE, reversal.getId(),
                        reversal.getReversalDate(), reversal.getBounceCharge(), false));
        paymentRepository.findApprovedByClientIdIn(clientIds).forEach(payment ->
                accountBills.addPayment(payment.getClientId(), payment.getPaidAt(), payment.getAmount()));
        corrections.accountWideAdjustments().forEach(adjustment ->
                accountBills.addPayment(adjustment.getClientId(), adjustment.getAdjustmentDate(),
                        adjustment.signedAmount().negate()));

        Map<Long, CreditBillLedger.Standing> standingByHolderId = new HashMap<>();
        for (Long holderId : new HashSet<>(holderIdByClientId.values())) {
            CreditClient holder = clientById.get(holderId);
            if (holder == null) {
                continue;
            }
            CreditBillLedger.Standing standing = CreditBillLedger.standingOf(
                    accountBills.billsByHolderId.getOrDefault(holderId, List.of()),
                    accountBills.paymentsByHolderId.getOrDefault(holderId, List.of()),
                    holder.getBillingCycle(), today)
                    .plusDisputed(accountBills.disputedByHolderId.getOrDefault(holderId, BigDecimal.ZERO));
            standingByHolderId.put(holderId, holder.getWrittenOffOn() != null ? standing.notOverdue() : standing);
        }
        return standingByHolderId;
    }

    /**
     * Bills and payments by account. A debit counts at what is left of it after the credit notes naming it; an openly
     * disputed debit is owed but not chased. Adjustments naming no debit clear the oldest bills like a payment, and an
     * advance refund takes back part of what was paid.
     */
    private static final class AccountBills {
        private final Map<Long, Long> holderIdByClientId;
        private final CreditDebitCorrections corrections;
        private final Map<Long, List<CreditBillLedger.Bill>> billsByHolderId = new HashMap<>();
        private final Map<Long, List<CreditBillLedger.Payment>> paymentsByHolderId = new HashMap<>();
        private final Map<Long, BigDecimal> disputedByHolderId = new HashMap<>();

        private AccountBills(Map<Long, Long> holderIdByClientId, CreditDebitCorrections corrections) {
            this.holderIdByClientId = holderIdByClientId;
            this.corrections = corrections;
        }

        void addUncorrectable(Long clientId, LocalDate date, BigDecimal amount) {
            billsByHolderId.computeIfAbsent(holderIdByClientId.get(clientId), id -> new ArrayList<>())
                    .add(new CreditBillLedger.Bill(date, amount, true));
        }

        void add(Long clientId, CreditDebitType type, Long debitId, LocalDate date, BigDecimal amount, boolean isSale) {
            BigDecimal remaining = corrections.remainingOf(type, debitId, amount);
            if (remaining.signum() <= 0) {
                return;
            }
            Long holderId = holderIdByClientId.get(clientId);
            if (corrections.isDisputed(type, debitId)) {
                disputedByHolderId.merge(holderId, remaining, BigDecimal::add);
                return;
            }
            billsByHolderId.computeIfAbsent(holderId, id -> new ArrayList<>())
                    .add(new CreditBillLedger.Bill(date, remaining, isSale));
        }

        void addPayment(Long clientId, LocalDate date, BigDecimal amount) {
            paymentsByHolderId.computeIfAbsent(holderIdByClientId.get(clientId), id -> new ArrayList<>())
                    .add(new CreditBillLedger.Payment(date, amount));
        }
    }

    /** The standing of the account a limit holder heads, its sub-accounts included. */
    @Transactional(readOnly = true)
    public CreditBillLedger.Standing standingOfAccount(CreditClient limitHolder, LocalDate today) {
        List<CreditClient> group = new ArrayList<>();
        group.add(limitHolder);
        group.addAll(clientRepository.findByParentClientId(limitHolder.getId()));
        return standingByHolderId(group, today).getOrDefault(limitHolder.getId(), CreditBillLedger.Standing.NOTHING_OWED);
    }
}
