package com.ppms.collection;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditAccountPolicyService;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditPayment;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Promises to pay. An open promise pauses reminders until its date; the daily run marks it KEPT once the account has
 * paid the amount since it was recorded, or MISSED the day after its date, which tells the managers and lets the
 * reminders resume.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditPromiseService {

    private final CreditPaymentPromiseRepository promiseRepository;
    private final CreditClientAccessService clientAccessService;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditClientRepository clientRepository;
    private final CreditPaymentRepository paymentRepository;
    private final NotificationDedupService notificationDedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    /** A promise for a sub-account is recorded on its parent, whose group the payments are counted over. */
    @Transactional
    public PromiseResponse recordPromise(Long pumpId, Long clientId, RecordPromiseRequest request, User currentUser) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        CreditClient account = accountPolicyService.lockLimitHolder(client);
        LocalDate today = businessClock.today();
        if (request.promisedDate().isBefore(today)) {
            throw new BusinessException(ErrorCodes.CREDIT_PROMISE_DATE_IN_PAST, Map.of(),
                    "The promised date cannot be before today.");
        }
        promiseRepository.findByClientIdAndStatus(account.getId(), PromiseStatus.OPEN).ifPresent(open -> {
            throw new BusinessException(ErrorCodes.CREDIT_PROMISE_ALREADY_OPEN,
                    Map.of("clientName", account.getName(), "amount", open.getAmount().toPlainString(),
                            "promisedDate", open.getPromisedDate().toString()),
                    "'" + account.getName() + "' already promised ₹" + open.getAmount() + " by "
                            + open.getPromisedDate() + ". Wait until that promise is kept or missed.");
        });

        CreditPaymentPromise promise = promiseRepository.save(CreditPaymentPromise.builder()
                .pumpId(pumpId)
                .clientId(account.getId())
                .amount(request.amount().setScale(2, RoundingMode.HALF_UP))
                .promisedDate(request.promisedDate())
                .recordedOn(today)
                .note(request.note() == null || request.note().isBlank() ? null : request.note().trim())
                .recordedByUserId(currentUser.getId())
                .build());

        auditService.log(pumpId, AuditAction.CREDIT_PROMISE_RECORDED, "CreditPaymentPromise", promise.getId().toString(),
                "Promise from clientId=" + account.getId() + " to pay ₹" + promise.getAmount() + " by "
                        + promise.getPromisedDate(), currentUser);
        return PromiseResponse.of(promise, account.getName(), currentUser.getFullName());
    }

    /**
     * Resolves the pump's open promises: KEPT once the account's approved payments dated on or after the day of the
     * promise reach the amount, MISSED once the promised date has passed without them. Returns how many were missed.
     */
    @Transactional
    public int resolvePromises(Long pumpId) {
        List<CreditPaymentPromise> open = promiseRepository.findByPumpIdAndStatus(pumpId, PromiseStatus.OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        LocalDate today = businessClock.today();
        Map<Long, BigDecimal> paidSinceByPromiseId = paidSincePromised(open);
        Map<Long, CreditClient> accountById = clientRepository.findAllById(
                        open.stream().map(CreditPaymentPromise::getClientId).toList()).stream()
                .collect(Collectors.toMap(CreditClient::getId, Function.identity()));

        int missed = 0;
        for (CreditPaymentPromise promise : open) {
            BigDecimal paid = paidSinceByPromiseId.get(promise.getId());
            if (paid.compareTo(promise.getAmount()) >= 0) {
                resolve(promise, PromiseStatus.KEPT, paid);
            } else if (promise.getPromisedDate().isBefore(today)) {
                resolve(promise, PromiseStatus.MISSED, paid);
                notifyMissed(promise, accountById.get(promise.getClientId()).getName(), paid);
                missed++;
            }
        }
        log.info("Promises resolved for pump={}: open={}, missed={}", pumpId, open.size(), missed);
        return missed;
    }

    /** Approved payments of each promise's account group dated on or after the day it was recorded, in two queries. */
    private Map<Long, BigDecimal> paidSincePromised(List<CreditPaymentPromise> promises) {
        List<Long> accountIds = promises.stream().map(CreditPaymentPromise::getClientId).distinct().toList();
        Map<Long, Long> accountIdByClientId = new HashMap<>();
        accountIds.forEach(id -> accountIdByClientId.put(id, id));
        clientRepository.findByParentClientIdIn(accountIds)
                .forEach(child -> accountIdByClientId.put(child.getId(), child.getParentClientId()));
        LocalDate earliest = promises.stream().map(CreditPaymentPromise::getRecordedOn)
                .min(Comparator.naturalOrder()).orElseThrow();
        Map<Long, List<CreditPayment>> paymentsByAccountId = paymentRepository
                .findApprovedByClientIdInPaidFrom(new ArrayList<>(accountIdByClientId.keySet()), earliest).stream()
                .collect(Collectors.groupingBy(payment -> accountIdByClientId.get(payment.getClientId())));

        Map<Long, BigDecimal> paidById = new HashMap<>();
        for (CreditPaymentPromise promise : promises) {
            paidById.put(promise.getId(), paymentsByAccountId.getOrDefault(promise.getClientId(), List.of()).stream()
                    .filter(payment -> !payment.getPaidAt().isBefore(promise.getRecordedOn()))
                    .map(CreditPayment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP));
        }
        return paidById;
    }

    private void resolve(CreditPaymentPromise promise, PromiseStatus status, BigDecimal paid) {
        promise.setStatus(status);
        promise.setPaidAmount(paid);
        promise.setResolvedAt(businessClock.now());
        promiseRepository.save(promise);
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
