package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.common.time.BusinessClock;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Security deposits: held apart from the balance and never used on their own. An owner may apply part of a deposit to
 * the dues, which lowers the balance like a payment, or refund it, for instance when the account is closed. Cash
 * received or refunded moves through the safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditDepositService {

    private final CreditClientAccessService clientAccessService;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditInterestService interestService;
    private final CreditDepositRepository depositRepository;
    private final CreditAdjustmentRepository adjustmentRepository;
    private final CashDrawerService cashDrawerService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public CreditDeposit receive(Long pumpId, Long clientId, MoneyMovementRequest request, User receivedBy) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        accountPolicyService.lockLimitHolder(client);
        CreditDeskRules.requireOpenAccount(client);
        PaymentMode mode = CreditAdvanceService.requiredMode(request.paymentMode());
        BigDecimal amount = CreditDeskRules.money(request.amount());
        LocalDate receivedOn = request.date() != null ? request.date() : businessClock.today();
        closedDayGuard.requireOpen(pumpId, receivedOn);
        Long cashEventId = mode == PaymentMode.CASH
                ? cashDrawerService.recordAutomaticCashIn(pumpId, amount, receivedOn,
                        "Security deposit from " + client.getName(), receivedBy).getId()
                : null;
        CreditDeposit deposit = depositRepository.save(CreditDeposit.builder()
                .pumpId(pumpId)
                .clientId(clientId)
                .kind(CreditDepositKind.RECEIVED)
                .amount(amount)
                .movementDate(receivedOn)
                .paymentMode(mode)
                .referenceNo(CreditPaymentReferenceCheck.trimmedOrNull(request.referenceNo()))
                .reason(CreditPaymentReferenceCheck.trimmedOrNull(request.reason()))
                .cashEventId(cashEventId)
                .createdBy(receivedBy.getId())
                .build());
        auditService.log(pumpId, AuditAction.CREDIT_DEPOSIT_RECEIVED, "CreditDeposit", deposit.getId().toString(),
                "Security deposit ₹" + amount + " received from clientId=" + clientId + " by " + mode + " on " + receivedOn,
                receivedBy);
        log.info("Security deposit received: pump={} client={} amount={} mode={}", pumpId, clientId, amount, mode);
        return deposit;
    }

    /** Lowers the group's dues by part of the deposit, today; at most the deposit held and what the group owes. */
    @Transactional
    public CreditDeposit applyToDues(Long pumpId, Long clientId, MoneyMovementRequest request, User appliedBy) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        CreditClient holder = accountPolicyService.lockLimitHolder(client);
        String reason = CreditDeskRules.requiredReason(request.reason());
        BigDecimal amount = CreditDeskRules.money(request.amount());
        LocalDate today = businessClock.today();
        closedDayGuard.requireOpen(pumpId, today);
        CreditDeskRules.requireAtMost(amount, depositRepository.heldByClientId(clientId),
                ErrorCodes.CREDIT_DEPOSIT_EXCEEDS_HELD, "At most the deposit held can be used");
        CreditDeskRules.requireAtMost(amount, interestService.computeOutstandingWithChildren(holder.getId()).max(BigDecimal.ZERO),
                ErrorCodes.CREDIT_DEPOSIT_EXCEEDS_DUES, "At most what the account owes can be paid from the deposit");

        CreditAdjustment applied = adjustmentRepository.save(CreditAdjustment.builder()
                .pumpId(pumpId)
                .clientId(clientId)
                .kind(CreditAdjustmentKind.DEPOSIT_APPLIED)
                .amount(amount)
                .adjustmentDate(today)
                .reason(reason)
                .createdBy(appliedBy.getId())
                .build());
        CreditDeposit movement = depositRepository.save(CreditDeposit.builder()
                .pumpId(pumpId)
                .clientId(clientId)
                .kind(CreditDepositKind.APPLIED)
                .amount(amount)
                .movementDate(today)
                .reason(reason)
                .adjustmentId(applied.getId())
                .createdBy(appliedBy.getId())
                .build());
        interestService.recalculateChargedInterestFrom(clientId, today,
                "Recalculated after the security deposit was applied on " + today, appliedBy);
        auditService.log(pumpId, AuditAction.CREDIT_DEPOSIT_APPLIED, "CreditDeposit", movement.getId().toString(),
                "Security deposit ₹" + amount + " applied to the dues of clientId=" + clientId + ", reason=" + reason,
                appliedBy);
        log.info("Security deposit applied: pump={} client={} amount={} by={}", pumpId, clientId, amount, appliedBy.getId());
        return movement;
    }

    @Transactional
    public CreditDeposit refund(Long pumpId, Long clientId, MoneyMovementRequest request, User refundedBy) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        accountPolicyService.lockLimitHolder(client);
        String reason = CreditDeskRules.requiredReason(request.reason());
        PaymentMode mode = CreditAdvanceService.requiredMode(request.paymentMode());
        BigDecimal amount = CreditDeskRules.money(request.amount());
        LocalDate today = businessClock.today();
        closedDayGuard.requireOpen(pumpId, today);
        CreditDeskRules.requireAtMost(amount, depositRepository.heldByClientId(clientId),
                ErrorCodes.CREDIT_DEPOSIT_EXCEEDS_HELD, "At most the deposit held can be refunded");
        Long cashEventId = mode == PaymentMode.CASH
                ? cashDrawerService.recordAutomaticCashOut(pumpId, amount, today,
                        "Security deposit refunded to " + client.getName(), refundedBy).getId()
                : null;
        CreditDeposit movement = depositRepository.save(CreditDeposit.builder()
                .pumpId(pumpId)
                .clientId(clientId)
                .kind(CreditDepositKind.REFUNDED)
                .amount(amount)
                .movementDate(today)
                .paymentMode(mode)
                .referenceNo(CreditPaymentReferenceCheck.trimmedOrNull(request.referenceNo()))
                .reason(reason)
                .cashEventId(cashEventId)
                .createdBy(refundedBy.getId())
                .build());
        auditService.log(pumpId, AuditAction.CREDIT_DEPOSIT_REFUNDED, "CreditDeposit", movement.getId().toString(),
                "Security deposit ₹" + amount + " refunded to clientId=" + clientId + " by " + mode + ", reason=" + reason,
                refundedBy);
        log.info("Security deposit refunded: pump={} client={} amount={} mode={}", pumpId, clientId, amount, mode);
        return movement;
    }

    @Transactional(readOnly = true)
    public List<CreditDeposit> movementsOf(Long pumpId, Long clientId) {
        clientAccessService.requireClientForPump(pumpId, clientId);
        return depositRepository.findByClientIdOrderByMovementDateDescIdDesc(clientId);
    }
}
