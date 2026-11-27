package com.ppms.collection;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditAccountPolicyService;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditInterestService;
import com.ppms.credit.CreditPayment;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Promises to pay. An open promise pauses reminders until its date. The daily run marks it KEPT once the account owes
 * nothing, or once its approved payments dated from the day it was recorded up to its date reach the amount; it marks
 * it MISSED after its date otherwise, which tells the managers and lets the reminders resume. A promise whose payments
 * reach the amount only with payments still awaiting approval stays open until they are approved or rejected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditPromiseService {

    static final int MAX_DAYS_AHEAD = 90;
    private static final String UNKNOWN_STAFF_NAME = "Unknown";

    private final CreditPaymentPromiseRepository promiseRepository;
    private final CreditPaymentPromiseChangeRepository promiseChangeRepository;
    private final CreditClientAccessService clientAccessService;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditClientRepository clientRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditInterestService interestService;
    private final UserRepository userRepository;
    private final NotificationDedupService notificationDedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    private record PaidTowardsPromise(BigDecimal approved, BigDecimal pending) {}

    /** A promise for a sub-account is recorded on its parent, whose group the payments are counted over. */
    @Transactional
    public PromiseResponse recordPromise(Long pumpId, Long clientId, RecordPromiseRequest request, User currentUser) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        CreditClient account = accountPolicyService.lockLimitHolder(client);
        LocalDate today = businessClock.today();
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        requireDateWithinReach(request.promisedDate(), today);
        requireAmountWithinOutstanding(amount, interestService.computeOutstandingWithChildren(account.getId()));
        promiseRepository.findByClientIdAndStatus(account.getId(), PromiseStatus.OPEN).ifPresent(open -> {
            throw new BusinessException(ErrorCodes.CREDIT_PROMISE_ALREADY_OPEN,
                    Map.of("clientName", account.getName(), "amount", open.getAmount().toPlainString(),
                            "promisedDate", open.getPromisedDate().toString()),
                    "'" + account.getName() + "' already promised ₹" + open.getAmount() + " by "
                            + open.getPromisedDate() + ". Edit or cancel that promise first.");
        });

        CreditPaymentPromise promise = promiseRepository.save(CreditPaymentPromise.builder()
                .pumpId(pumpId)
                .clientId(account.getId())
                .amount(amount)
                .promisedDate(request.promisedDate())
                .recordedOn(today)
                .note(trimmedOrNull(request.note()))
                .recordedByUserId(currentUser.getId())
                .build());

        auditService.log(pumpId, AuditAction.CREDIT_PROMISE_RECORDED, "CreditPaymentPromise", promise.getId().toString(),
                "Promise from clientId=" + account.getId() + " to pay ₹" + promise.getAmount() + " by "
                        + promise.getPromisedDate(), currentUser);
        return PromiseResponse.of(promise, account.getName(), currentUser.getFullName());
    }

    /**
     * Changes an open promise's amount, date or note. The amount may be up to what the account owes now plus what it
     * has already paid towards the promise.
     */
    @Transactional
    public PromiseResponse editPromise(Long pumpId, Long promiseId, EditPromiseRequest request, User currentUser) {
        CreditPaymentPromise promise = requireOpenPromise(pumpId, promiseId);
        CreditClient account = requireAccount(promise.getClientId());
        LocalDate today = businessClock.today();
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        requireDateWithinReach(request.promisedDate(), today);
        BigDecimal paidSoFar = paidTowards(List.of(promise)).get(promise.getId()).approved();
        requireAmountWithinOutstanding(amount,
                interestService.computeOutstandingWithChildren(account.getId()).add(paidSoFar));

        CreditPaymentPromiseChange change = promiseChangeRepository.save(CreditPaymentPromiseChange.builder()
                .promiseId(promise.getId())
                .changeType(PromiseChangeType.EDITED)
                .previousAmount(promise.getAmount())
                .previousPromisedDate(promise.getPromisedDate())
                .newAmount(amount)
                .newPromisedDate(request.promisedDate())
                .reason(trimmedOrNull(request.reason()))
                .changedByUserId(currentUser.getId())
                .build());
        promise.setAmount(amount);
        promise.setPromisedDate(request.promisedDate());
        promise.setNote(trimmedOrNull(request.note()));
        promiseRepository.save(promise);

        auditService.log(pumpId, AuditAction.CREDIT_PROMISE_EDITED, "CreditPaymentPromise", promise.getId().toString(),
                "Promise from clientId=" + account.getId() + " changed from ₹" + change.getPreviousAmount() + " by "
                        + change.getPreviousPromisedDate() + " to ₹" + amount + " by " + request.promisedDate()
                        + (change.getReason() != null ? ": " + change.getReason() : ""), currentUser);
        log.info("Promise edited: pump={}, promise={}, amount={}, promisedDate={}, by={}",
                pumpId, promise.getId(), amount, request.promisedDate(), currentUser.getId());
        return PromiseResponse.of(promise, account.getName(), recordedByName(promise, currentUser));
    }

    /** Withdraws an open promise, keeping what was paid towards it so far; reminders resume for the account. */
    @Transactional
    public PromiseResponse cancelPromise(Long pumpId, Long promiseId, CancelPromiseRequest request, User currentUser) {
        CreditPaymentPromise promise = requireOpenPromise(pumpId, promiseId);
        CreditClient account = requireAccount(promise.getClientId());
        String reason = request.reason().trim();

        promiseChangeRepository.save(CreditPaymentPromiseChange.builder()
                .promiseId(promise.getId())
                .changeType(PromiseChangeType.CANCELLED)
                .previousAmount(promise.getAmount())
                .previousPromisedDate(promise.getPromisedDate())
                .reason(reason)
                .changedByUserId(currentUser.getId())
                .build());
        resolve(promise, PromiseStatus.CANCELLED, paidTowards(List.of(promise)).get(promise.getId()).approved());

        auditService.log(pumpId, AuditAction.CREDIT_PROMISE_CANCELLED, "CreditPaymentPromise", promise.getId().toString(),
                "Promise from clientId=" + account.getId() + " to pay ₹" + promise.getAmount() + " by "
                        + promise.getPromisedDate() + " cancelled: " + reason, currentUser);
        log.info("Promise cancelled: pump={}, promise={}, by={}", pumpId, promise.getId(), currentUser.getId());
        return PromiseResponse.of(promise, account.getName(), recordedByName(promise, currentUser));
    }

    /** Resolves the pump's open promises as the class describes. Returns how many were missed. */
    @Transactional
    public int resolvePromises(Long pumpId) {
        List<CreditPaymentPromise> open = promiseRepository.findByPumpIdAndStatus(pumpId, PromiseStatus.OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        LocalDate today = businessClock.today();
        Map<Long, Long> accountIdByClientId = accountIdByGroupClientId(
                open.stream().map(CreditPaymentPromise::getClientId).distinct().toList());
        Map<Long, PaidTowardsPromise> paidByPromiseId = paidTowards(open, accountIdByClientId);
        Map<Long, BigDecimal> outstandingByAccountId = new HashMap<>();
        interestService.computeOutstandingBatch(accountIdByClientId.keySet()).forEach((clientId, outstanding) ->
                outstandingByAccountId.merge(accountIdByClientId.get(clientId), outstanding, BigDecimal::add));
        Map<Long, CreditClient> accountById = clientRepository.findAllById(
                        open.stream().map(CreditPaymentPromise::getClientId).toList()).stream()
                .collect(Collectors.toMap(CreditClient::getId, Function.identity()));

        int missed = 0;
        for (CreditPaymentPromise promise : open) {
            PaidTowardsPromise paid = paidByPromiseId.get(promise.getId());
            boolean owesNothing = outstandingByAccountId.getOrDefault(promise.getClientId(), BigDecimal.ZERO).signum() <= 0;
            if (owesNothing || paid.approved().compareTo(promise.getAmount()) >= 0) {
                resolve(promise, PromiseStatus.KEPT, paid.approved());
            } else if (promise.getPromisedDate().isBefore(today)
                    && paid.approved().add(paid.pending()).compareTo(promise.getAmount()) < 0) {
                resolve(promise, PromiseStatus.MISSED, paid.approved());
                notifyMissed(promise, accountById.get(promise.getClientId()).getName(), paid.approved());
                missed++;
            }
        }
        log.info("Promises resolved for pump={}: open={}, missed={}", pumpId, open.size(), missed);
        return missed;
    }

    private CreditPaymentPromise requireOpenPromise(Long pumpId, Long promiseId) {
        CreditPaymentPromise promise = promiseRepository.findByIdAndPumpId(promiseId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_PROMISE,
                        "Promise not found: " + promiseId));
        accountPolicyService.lockLimitHolderOfClient(promise.getClientId());
        if (promise.getStatus() != PromiseStatus.OPEN) {
            throw new BusinessException(ErrorCodes.CREDIT_PROMISE_NOT_OPEN,
                    Map.of("statusCode", promise.getStatus().name()),
                    "This promise is already " + promise.getStatus() + ", so it can no longer be changed.");
        }
        return promise;
    }

    private CreditClient requireAccount(Long accountId) {
        return clientRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Credit client not found: " + accountId));
    }

    private static void requireDateWithinReach(LocalDate promisedDate, LocalDate today) {
        if (promisedDate.isBefore(today)) {
            throw new BusinessException(ErrorCodes.CREDIT_PROMISE_DATE_IN_PAST, Map.of(),
                    "The promised date cannot be before today.");
        }
        LocalDate latest = today.plusDays(MAX_DAYS_AHEAD);
        if (promisedDate.isAfter(latest)) {
            throw new BusinessException(ErrorCodes.CREDIT_PROMISE_DATE_TOO_FAR,
                    Map.of("maxDays", MAX_DAYS_AHEAD, "latestDate", latest.toString()),
                    "The promised date can be at most " + MAX_DAYS_AHEAD + " days ahead, by " + latest + ".");
        }
    }

    private static void requireAmountWithinOutstanding(BigDecimal amount, BigDecimal outstanding) {
        BigDecimal owed = outstanding.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(owed) > 0) {
            throw new BusinessException(ErrorCodes.CREDIT_PROMISE_EXCEEDS_OUTSTANDING,
                    Map.of("amount", amount.toPlainString(), "outstanding", owed.toPlainString()),
                    "The promised amount ₹" + amount + " is more than the ₹" + owed + " the account owes.");
        }
    }

    private Map<Long, PaidTowardsPromise> paidTowards(List<CreditPaymentPromise> promises) {
        return paidTowards(promises, accountIdByGroupClientId(
                promises.stream().map(CreditPaymentPromise::getClientId).distinct().toList()));
    }

    /**
     * Approved and pending payments of each promise's account group dated from the day it was recorded up to its
     * date, in one query.
     */
    private Map<Long, PaidTowardsPromise> paidTowards(List<CreditPaymentPromise> promises,
                                                       Map<Long, Long> accountIdByClientId) {
        LocalDate earliest = promises.stream().map(CreditPaymentPromise::getRecordedOn)
                .min(Comparator.naturalOrder()).orElseThrow();
        Map<Long, List<CreditPayment>> paymentsByAccountId = paymentRepository
                .findApprovedOrPendingByClientIdInPaidFrom(accountIdByClientId.keySet(), earliest).stream()
                .collect(Collectors.groupingBy(payment -> accountIdByClientId.get(payment.getClientId())));

        Map<Long, PaidTowardsPromise> paidById = new HashMap<>();
        for (CreditPaymentPromise promise : promises) {
            List<CreditPayment> payments = paymentsByAccountId.getOrDefault(promise.getClientId(), List.of()).stream()
                    .filter(payment -> !payment.getPaidAt().isBefore(promise.getRecordedOn())
                            && !payment.getPaidAt().isAfter(promise.getPromisedDate()))
                    .toList();
            paidById.put(promise.getId(), new PaidTowardsPromise(
                    sumOf(payments, ExpenseApprovalStatus.APPROVED),
                    sumOf(payments, ExpenseApprovalStatus.PENDING_APPROVAL)));
        }
        return paidById;
    }

    private static BigDecimal sumOf(List<CreditPayment> payments, ExpenseApprovalStatus status) {
        return payments.stream()
                .filter(payment -> payment.getPaymentApprovalStatus() == status)
                .map(CreditPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Each account and its sub-accounts, mapped to the account. */
    private Map<Long, Long> accountIdByGroupClientId(Collection<Long> accountIds) {
        Map<Long, Long> accountIdByClientId = new HashMap<>();
        accountIds.forEach(id -> accountIdByClientId.put(id, id));
        clientRepository.findByParentClientIdIn(accountIds)
                .forEach(child -> accountIdByClientId.put(child.getId(), child.getParentClientId()));
        return accountIdByClientId;
    }

    private String recordedByName(CreditPaymentPromise promise, User currentUser) {
        if (promise.getRecordedByUserId().equals(currentUser.getId())) {
            return currentUser.getFullName();
        }
        return userRepository.findById(promise.getRecordedByUserId())
                .map(User::getFullName)
                .orElse(UNKNOWN_STAFF_NAME);
    }

    private void resolve(CreditPaymentPromise promise, PromiseStatus status, BigDecimal paid) {
        promise.setStatus(status);
        promise.setPaidAmount(paid);
        promise.setResolvedAt(businessClock.now());
        promiseRepository.save(promise);
    }

    private static String trimmedOrNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private void notifyMissed(CreditPaymentPromise promise, String clientName, BigDecimal paid) {
        notificationDedupService.maybeInsert(promise.getPumpId(), NotificationType.CREDIT_PROMISE_MISSED,
                "CREDIT_PROMISE_MISSED:client:" + promise.getClientId() + ":promise:" + promise.getId(),
                "Promise to Pay Missed",
                clientName + " promised ₹" + promise.getAmount() + " by " + promise.getPromisedDate() + " but paid ₹"
                        + paid + ". Reminders have resumed.",
                Map.of("clientName", clientName, "amount", promise.getAmount().toPlainString(),
                        "promisedDate", promise.getPromisedDate().toString(), "paidAmount", paid.toPlainString(),
                        "clientId", promise.getClientId()));
    }
}
