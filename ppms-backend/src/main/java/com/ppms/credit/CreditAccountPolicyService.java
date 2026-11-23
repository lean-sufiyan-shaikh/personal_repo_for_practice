package com.ppms.credit;

import com.ppms.common.time.BusinessClock;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class CreditAccountPolicyService {

    private final CreditClientRepository creditClientRepository;
    private final CreditInterestService creditInterestService;
    private final CreditExtensionRepository creditExtensionRepository;
    private final CreditPaymentRepository creditPaymentRepository;
    private final BusinessClock businessClock;
    private final EntityManager entityManager;

    /**
     * Finds the client a credit entry is for, by id or else by exact name. Every credit entry must
     * belong to an existing client of the pump, so an unknown client is refused.
     */
    public CreditClient requireClientForCreditEntry(Long pumpId, Long clientId, String clientName) {
        if (clientId != null) {
            return creditClientRepository.findById(clientId)
                    .filter(client -> client.getPumpId().equals(pumpId))
                    .orElseThrow(() -> new BusinessException(
                            "Credit client #" + clientId + " does not exist at this pump. Pick a client from the list."));
        }
        if (clientName == null || clientName.isBlank()) {
            throw new BusinessException("Pick a credit client for every credit entry.");
        }
        return creditClientRepository.findByPumpIdAndName(pumpId, clientName.trim())
                .orElseThrow(() -> new BusinessException(
                        "No credit client named '" + clientName.trim() + "' exists at this pump. "
                                + "Add the client on the Credit page first, then pick it from the list."));
    }

    /** Refuses credit to a disabled client, and to a sub-account whose parent account is disabled. */
    public void requireClientAcceptsCredit(CreditClient client) {
        if (!client.isActive()) {
            throw new BusinessException("Credit is disabled for '" + client.getName()
                    + "'. Enable the client on the Credit page before giving it credit.");
        }
        if (client.getParentClientId() == null) {
            return;
        }
        CreditClient parent = creditClientRepository.findById(client.getParentClientId())
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found: " + client.getParentClientId()));
        if (!parent.isActive()) {
            throw new BusinessException("Credit is disabled for '" + client.getName() + "' because its parent account '"
                    + parent.getName() + "' is disabled.");
        }
    }

    /**
     * Checks credit entries captured at shift close against each group's limit. Group rows are
     * locked first, and entries for the same group are added together before each check.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateCreditLimits(Map<CreditClient, BigDecimal> amountByClient) {
        amountByClient.keySet().forEach(this::requireClientAcceptsCredit);
        Map<Long, BigDecimal> amountByHolderId = new HashMap<>();
        amountByClient.forEach((client, amount) ->
                amountByHolderId.merge(limitHolderId(client), amount, BigDecimal::add));

        lockLimitHolders(amountByHolderId.keySet());

        for (Map.Entry<Long, BigDecimal> holderAmount : amountByHolderId.entrySet()) {
            CreditClient limitHolder = creditClientRepository.findById(holderAmount.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Credit client not found: " + holderAmount.getKey()));
            if (limitHolder.getCreditLimit().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal amount = holderAmount.getValue();
            BigDecimal combinedOutstanding = creditInterestService.computeOutstandingWithChildren(limitHolder.getId());
            BigDecimal projectedOutstanding = combinedOutstanding.add(amount).setScale(2, RoundingMode.HALF_UP);
            if (projectedOutstanding.compareTo(limitHolder.getCreditLimit()) > 0) {
                throw new BusinessException(
                        "Credit limit exceeded for '" + limitHolder.getName() + "'. " +
                                "Current outstanding (group): " + combinedOutstanding.setScale(2, RoundingMode.HALF_UP) +
                                ", entries in this shift: " + amount.setScale(2, RoundingMode.HALF_UP) +
                                ", limit: " + limitHolder.getCreditLimit() + ". " +
                                "Please record a payment before adding more credit.");
            }
        }
    }

    public Long limitHolderId(CreditClient client) {
        return client.getParentClientId() != null ? client.getParentClientId() : client.getId();
    }

    /**
     * Locks the group's limit-holder row and reloads it, so a copy already held in this
     * transaction cannot hide a concurrent limit change.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditClient lockLimitHolder(CreditClient client) {
        Long holderId = limitHolderId(client);
        CreditClient holder = creditClientRepository.findByIdForUpdate(holderId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found: " + holderId));
        entityManager.refresh(holder);
        return holder;
    }

    /** Locks the limit holders of several clients, in holder id order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockLimitHoldersOfClients(Collection<Long> clientIds) {
        List<Long> holderIds = creditClientRepository.findAllById(clientIds).stream()
                .map(this::limitHolderId)
                .toList();
        lockLimitHolders(holderIds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockLimitHolderOfClient(Long clientId) {
        CreditClient client = creditClientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found: " + clientId));
        lockLimitHolder(client);
    }

    /** Locks limit-holder rows in ascending id order so concurrent callers cannot deadlock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockLimitHolders(Collection<Long> holderIds) {
        for (Long holderId : new TreeSet<>(holderIds)) {
            CreditClient holder = creditClientRepository.findByIdForUpdate(holderId)
                    .orElseThrow(() -> new ResourceNotFoundException("Credit client not found: " + holderId));
            entityManager.refresh(holder);
        }
    }

    /**
     * Applies the stricter mid-shift sale rules. Close-shift entry capture only validates the limit,
     * while real-time credit sales must also respect approved extensions and overdue billing-cycle rules.
     *
     * Acquires a PESSIMISTIC WRITE lock on the limit-holder row before reading the outstanding
     * balance. This serialises concurrent credit sales for the same client so two operators at
     * the same pump cannot simultaneously bypass the limit check (race condition fix).
     * The lock is held for the duration of the caller's @Transactional boundary (ShiftService.addCreditEntry).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateCreditSaleAllowed(Long pumpId, CreditClient client, BigDecimal amount) {
        requireClientAcceptsCredit(client);
        CreditClient limitHolder = lockLimitHolder(client);
        List<CreditExtension> activeExtensions = creditExtensionRepository
                .findInEffect(pumpId, limitHolder.getId(), businessClock.today());

        if (limitHolder.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal combinedOutstanding = creditInterestService.computeOutstandingWithChildren(limitHolder.getId());
            BigDecimal projected = combinedOutstanding.add(amount).setScale(2, RoundingMode.HALF_UP);
            BigDecimal extensionHeadroom = activeExtensions.stream()
                    .filter(extension -> extension.getExtensionType() == CreditExtensionType.AMOUNT_EXTENSION
                            && extension.getExtensionAmount() != null)
                    .map(CreditExtension::getExtensionAmount)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
            BigDecimal effectiveLimit = limitHolder.getCreditLimit().add(extensionHeadroom);

            if (projected.compareTo(effectiveLimit) > 0) {
                throw new BusinessException(
                        "Credit limit exceeded for '" + limitHolder.getName() + "'. " +
                                "Outstanding (group): " + combinedOutstanding.setScale(2, RoundingMode.HALF_UP) +
                                ", this entry: " + amount +
                                ", effective limit: " + effectiveLimit.setScale(2, RoundingMode.HALF_UP) + ".");
            }
        }

        boolean hasCycleWaiver = activeExtensions.stream().anyMatch(extension ->
                extension.getExtensionType() == CreditExtensionType.BILLING_CYCLE_EXTENSION
                        || extension.getExtensionType() == CreditExtensionType.OVERDUE_BLOCK_WAIVER);
        if (!hasCycleWaiver && isBillingCycleOverdue(limitHolder)) {
            throw new BusinessException(
                    "Credit sales blocked for '" + limitHolder.getName() + "': billing cycle overdue. " +
                            "The client must settle their outstanding balance or an Admin/Owner must grant a Credit Extension.");
        }
    }

    /**
     * The whole group counts: sub-account sales make the group owe, and a payment on any account of
     * the group keeps it current.
     */
    private boolean isBillingCycleOverdue(CreditClient limitHolder) {
        BigDecimal groupOutstanding = creditInterestService.computeOutstandingWithChildren(limitHolder.getId());
        if (groupOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        int cycleDays = limitHolder.getBillingCycle() == BillingCycle.WEEKLY ? 7 : 30;
        LocalDate overdueThreshold = businessClock.today().minusDays(cycleDays);

        List<Long> groupClientIds = new ArrayList<>();
        groupClientIds.add(limitHolder.getId());
        creditClientRepository.findByParentClientId(limitHolder.getId())
                .forEach(child -> groupClientIds.add(child.getId()));

        Optional<LocalDate> lastPayment = creditPaymentRepository.findLastPaymentDateByClientIds(groupClientIds);
        if (lastPayment.isPresent()) {
            return lastPayment.get().isBefore(overdueThreshold);
        }

        return creditInterestService.findOldestCreditSaleDate(groupClientIds)
                .map(oldestSaleDate -> oldestSaleDate.isBefore(overdueThreshold))
                .orElse(false);
    }
}
