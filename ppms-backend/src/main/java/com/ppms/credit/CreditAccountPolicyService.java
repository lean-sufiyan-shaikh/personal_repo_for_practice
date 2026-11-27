package com.ppms.credit;

import com.ppms.common.time.BusinessClock;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CreditAccountPolicyService {

    private final CreditClientRepository creditClientRepository;
    private final CreditInterestService creditInterestService;
    private final CreditExtensionRepository creditExtensionRepository;
    private final CreditOverdueService creditOverdueService;
    private final PumpLocationRepository pumpLocationRepository;
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
                    .orElseThrow(() -> new BusinessException(ErrorCodes.CREDIT_CLIENT_NOT_AT_PUMP,
                            Map.of("clientId", clientId),
                            "Credit client #" + clientId + " does not exist at this pump. Pick a client from the list."));
        }
        if (clientName == null || clientName.isBlank()) {
            throw new BusinessException(ErrorCodes.CREDIT_CLIENT_REQUIRED_FOR_ENTRY,
                    Map.of(),
                    "Pick a credit client for every credit entry.");
        }
        return creditClientRepository.findByPumpIdAndName(pumpId, clientName.trim())
                .orElseThrow(() -> new BusinessException(ErrorCodes.CREDIT_CLIENT_NAME_NOT_AT_PUMP,
                        Map.of("clientName", clientName.trim()),
                        "No credit client named '" + clientName.trim() + "' exists at this pump. "
                                + "Add the client on the Credit page first, then pick it from the list."));
    }

    /** Refuses credit to a disabled client, and to a sub-account whose parent account is disabled. */
    public void requireClientAcceptsCredit(CreditClient client) {
        if (!client.isActive()) {
            throw new BusinessException(ErrorCodes.CREDIT_CLIENT_DISABLED, Map.of("clientName", client.getName()),
                    "Credit is disabled for '" + client.getName()
                    + "'. Enable the client on the Credit page before giving it credit.");
        }
        if (client.getParentClientId() == null) {
            return;
        }
        CreditClient parent = creditClientRepository.findById(client.getParentClientId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Credit client not found: " + client.getParentClientId()));
        if (!parent.isActive()) {
            throw new BusinessException(ErrorCodes.CREDIT_PARENT_ACCOUNT_DISABLED,
                    Map.of("clientName", client.getName(), "parentName", parent.getName()),
                    "Credit is disabled for '" + client.getName() + "' because its parent account '"
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
                    .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                            "Credit client not found: " + holderAmount.getKey()));
            if (limitHolder.getCreditLimit().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal amount = holderAmount.getValue();
            BigDecimal combinedOutstanding = creditInterestService.computeOutstandingWithChildren(limitHolder.getId());
            BigDecimal projectedOutstanding = combinedOutstanding.add(amount).setScale(2, RoundingMode.HALF_UP);
            if (projectedOutstanding.compareTo(limitHolder.getCreditLimit()) > 0) {
                throw new BusinessException(ErrorCodes.CREDIT_LIMIT_EXCEEDED,
                        Map.of("clientName", limitHolder.getName(),
                                "outstanding", combinedOutstanding.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                "amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                "limit", limitHolder.getCreditLimit().setScale(2, RoundingMode.HALF_UP).toPlainString()),
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
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Credit client not found: " + holderId));
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
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Credit client not found: " + clientId));
        lockLimitHolder(client);
    }

    /** Locks limit-holder rows in ascending id order so concurrent callers cannot deadlock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockLimitHolders(Collection<Long> holderIds) {
        for (Long holderId : new TreeSet<>(holderIds)) {
            CreditClient holder = creditClientRepository.findByIdForUpdate(holderId)
                    .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                            "Credit client not found: " + holderId));
            entityManager.refresh(holder);
        }
    }

    /**
     * Applies the stricter mid-shift sale rules. Close-shift entry capture only validates the limit,
     * while real-time credit sales must also respect the credit hold, approved extensions and overdue
     * billing-cycle rules.
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
        long daysOverdue = groupDaysOverdue(limitHolder);

        Integer holdDays = creditHoldDaysOf(pumpId);
        if (isOnHold(holdDays, daysOverdue, activeExtensions)) {
            throw new BusinessException(ErrorCodes.CREDIT_ON_HOLD,
                    Map.of("clientName", limitHolder.getName(), "daysOverdue", daysOverdue, "holdDays", holdDays),
                    "Credit is on hold for '" + limitHolder.getName() + "': " + daysOverdue + " days overdue, and credit "
                            + "is held from " + holdDays + " days. The client must pay, or an Owner/Admin must override the hold.");
        }

        if (limitHolder.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal combinedOutstanding = creditInterestService.computeOutstandingWithChildren(limitHolder.getId());
            BigDecimal projected = combinedOutstanding.add(amount).setScale(2, RoundingMode.HALF_UP);
            BigDecimal effectiveLimit = effectiveLimit(limitHolder, activeExtensions);

            if (projected.compareTo(effectiveLimit) > 0) {
                throw new BusinessException(ErrorCodes.CREDIT_LIMIT_EXCEEDED,
                        Map.of("clientName", limitHolder.getName(),
                                "outstanding", combinedOutstanding.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                "amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                "limit", effectiveLimit.setScale(2, RoundingMode.HALF_UP).toPlainString()),
                        "Credit limit exceeded for '" + limitHolder.getName() + "'. " +
                                "Outstanding (group): " + combinedOutstanding.setScale(2, RoundingMode.HALF_UP) +
                                ", this entry: " + amount +
                                ", effective limit: " + effectiveLimit.setScale(2, RoundingMode.HALF_UP) + ".");
            }
        }

        if (!hasCycleWaiver(activeExtensions) && daysOverdue > 0) {
            throw new BusinessException(ErrorCodes.CREDIT_BILLING_CYCLE_OVERDUE,
                    Map.of("clientName", limitHolder.getName()),
                    "Credit sales blocked for '" + limitHolder.getName() + "': billing cycle overdue. " +
                            "The client must settle their outstanding balance or an Admin/Owner must grant a Credit Extension.");
        }
    }

    /** Sub-account sales make the group owe, and a payment on any account of the group clears its oldest bills. */
    private long groupDaysOverdue(CreditClient limitHolder) {
        return creditOverdueService.standingOfAccount(limitHolder, businessClock.today()).daysOverdue();
    }

    /** Null when the pump holds no credit for being overdue. */
    public Integer creditHoldDaysOf(Long pumpId) {
        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        return pump.getCreditHoldDays();
    }

    /** A credit hold override in effect lifts the hold; other extensions do not. */
    static boolean isOnHold(Integer holdDays, long daysOverdue, List<CreditExtension> activeExtensions) {
        return holdDays != null && daysOverdue >= holdDays && activeExtensions.stream()
                .noneMatch(extension -> extension.getExtensionType() == CreditExtensionType.CREDIT_HOLD_OVERRIDE);
    }

    /**
     * Whether each client of the pump could take a credit sale now, by the rules of validateCreditSaleAllowed:
     * a disabled client or parent first, then the credit hold unless overridden, then the group limit with any
     * amount extension, then the overdue billing cycle unless an extension waives it. Loads the whole pump in a fixed number of queries.
     */
    @Transactional(readOnly = true)
    public Map<Long, CreditClientStandingResponse> creditStandingOfPumpClients(Long pumpId) {
        List<CreditClient> clients = creditClientRepository.findByPumpIdOrderByNameAsc(pumpId);
        if (clients.isEmpty()) {
            return Map.of();
        }
        return creditStandingOfPumpClients(pumpId, clients,
                creditOverdueService.standingByHolderId(clients, businessClock.today()));
    }

    /** As above, for the pump's clients and their accounts' overdue standing already loaded. */
    Map<Long, CreditClientStandingResponse> creditStandingOfPumpClients(
            Long pumpId, List<CreditClient> clients, Map<Long, CreditBillLedger.Standing> overdueByHolderId) {
        Map<Long, CreditClient> clientById = clients.stream()
                .collect(Collectors.toMap(CreditClient::getId, Function.identity()));
        Set<Long> holderIds = clients.stream().map(this::limitHolderId).collect(Collectors.toSet());

        Map<Long, List<CreditExtension>> extensionsByClientId = creditExtensionRepository
                .findInEffectAtPump(pumpId, businessClock.today()).stream()
                .collect(Collectors.groupingBy(CreditExtension::getClientId));
        Integer holdDays = creditHoldDaysOf(pumpId);

        Map<Long, CreditClientStandingResponse> groupStandingByHolderId = new HashMap<>();
        holderIds.forEach(holderId -> {
            CreditClient holder = clientById.get(holderId);
            List<CreditExtension> extensions = extensionsByClientId.getOrDefault(holderId, List.of());
            CreditBillLedger.Standing overdue = overdueByHolderId.getOrDefault(holderId, CreditBillLedger.Standing.NOTHING_OWED);
            BigDecimal groupOutstanding = overdue.outstanding();
            BigDecimal availableCredit = null;
            CreditStatus status = CreditStatus.OK;
            if (holder.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
                availableCredit = effectiveLimit(holder, extensions).subtract(groupOutstanding).setScale(2, RoundingMode.HALF_UP);
                if (availableCredit.compareTo(BigDecimal.ZERO) <= 0) {
                    status = CreditStatus.OVER_LIMIT;
                    availableCredit = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                }
            }
            long daysOverdue = overdue.daysOverdue();
            if (isOnHold(holdDays, daysOverdue, extensions)) {
                status = CreditStatus.ON_HOLD;
            } else if (status == CreditStatus.OK && !hasCycleWaiver(extensions) && daysOverdue > 0) {
                status = CreditStatus.OVERDUE;
            }
            groupStandingByHolderId.put(holderId, new CreditClientStandingResponse(holderId, status, availableCredit));
        });

        Map<Long, CreditClientStandingResponse> standingById = new HashMap<>();
        for (CreditClient client : clients) {
            CreditClientStandingResponse group = groupStandingByHolderId.get(limitHolderId(client));
            CreditClient parent = client.getParentClientId() != null ? clientById.get(client.getParentClientId()) : null;
            boolean disabled = !client.isActive() || (parent != null && !parent.isActive());
            standingById.put(client.getId(), new CreditClientStandingResponse(client.getId(),
                    disabled ? CreditStatus.INACTIVE : group.creditStatus(), group.availableCredit()));
        }
        return standingById;
    }

    private static BigDecimal effectiveLimit(CreditClient limitHolder, List<CreditExtension> activeExtensions) {
        BigDecimal extensionHeadroom = activeExtensions.stream()
                .filter(extension -> extension.getExtensionType() == CreditExtensionType.AMOUNT_EXTENSION
                        && extension.getExtensionAmount() != null)
                .map(CreditExtension::getExtensionAmount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
        return limitHolder.getCreditLimit().add(extensionHeadroom);
    }

    /** A credit hold override lifts the overdue block too, or the client it lets buy would still be refused. */
    private static boolean hasCycleWaiver(List<CreditExtension> activeExtensions) {
        return activeExtensions.stream().anyMatch(extension ->
                extension.getExtensionType() == CreditExtensionType.BILLING_CYCLE_EXTENSION
                        || extension.getExtensionType() == CreditExtensionType.OVERDUE_BLOCK_WAIVER
                        || extension.getExtensionType() == CreditExtensionType.CREDIT_HOLD_OVERRIDE);
    }
}
