package com.ppms.credit;

import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.transaction.TransactionPaymentMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Loads any number of accounts in six queries.
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

        Map<Long, List<CreditBillLedger.Bill>> billsByHolderId = new HashMap<>();
        Map<Long, List<CreditBillLedger.Payment>> paymentsByHolderId = new HashMap<>();
        openingBalanceRepository.findByClientIdIn(clientIds).forEach(balance ->
                billsByHolderId.computeIfAbsent(holderIdByClientId.get(balance.getClientId()), id -> new ArrayList<>())
                        .add(new CreditBillLedger.Bill(balance.getAsOfDate(), balance.getAmount(), true)));
        creditEntryRepository.findActiveByClientIdIn(clientIds).forEach(entry ->
                billsByHolderId.computeIfAbsent(holderIdByClientId.get(entry.getClientId()), id -> new ArrayList<>())
                        .add(new CreditBillLedger.Bill(entry.getSaleDate(), entry.getAmount(), true)));
        ancillarySaleRepository.findByClientIdsAndPaymentMode(clientIds, TransactionPaymentMode.CREDIT).forEach(sale ->
                billsByHolderId.computeIfAbsent(holderIdByClientId.get(sale.getClientId()), id -> new ArrayList<>())
                        .add(new CreditBillLedger.Bill(sale.getSaleDate(), sale.getTotalWithGst(), true)));
        interestChargeRepository.findByClientIdInAndVoidedAtIsNull(clientIds).forEach(charge ->
                billsByHolderId.computeIfAbsent(holderIdByClientId.get(charge.getClientId()), id -> new ArrayList<>())
                        .add(new CreditBillLedger.Bill(charge.getPeriodTo(), charge.getAmount(), false)));
        reversalRepository.findWithBounceChargeByClientIdIn(clientIds).forEach(reversal ->
                billsByHolderId.computeIfAbsent(holderIdByClientId.get(reversal.getClientId()), id -> new ArrayList<>())
                        .add(new CreditBillLedger.Bill(reversal.getReversalDate(), reversal.getBounceCharge(), false)));
        paymentRepository.findApprovedByClientIdIn(clientIds).forEach(payment ->
                paymentsByHolderId.computeIfAbsent(holderIdByClientId.get(payment.getClientId()), id -> new ArrayList<>())
                        .add(new CreditBillLedger.Payment(payment.getPaidAt(), payment.getAmount())));

        Map<Long, CreditBillLedger.Standing> standingByHolderId = new HashMap<>();
        for (Long holderId : new HashSet<>(holderIdByClientId.values())) {
            CreditClient holder = clientById.get(holderId);
            if (holder == null) {
                continue;
            }
            standingByHolderId.put(holderId, CreditBillLedger.standingOf(
                    billsByHolderId.getOrDefault(holderId, List.of()),
                    paymentsByHolderId.getOrDefault(holderId, List.of()),
                    holder.getBillingCycle(), today));
        }
        return standingByHolderId;
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
