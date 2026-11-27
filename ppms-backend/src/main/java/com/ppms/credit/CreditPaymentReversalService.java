package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.expense.ExpenseApprovalStatus;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reverses an approved credit payment, such as a bounced cheque. The reversal is a new ledger row dated today, so
 * closed days keep their figures; the payment stays on the ledger marked REVERSED and stops counting towards the
 * balance, the interest already charged since its date is recalculated without it, and overdue, hold and collections
 * (which are worked out from the approved payments) treat it as never paid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditPaymentReversalService {

    private final CreditClientRepository clientRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditPaymentReversalRepository reversalRepository;
    private final CreditAccountPolicyService creditAccountPolicyService;
    private final CreditInterestService interestService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final NotificationDedupService notificationDedupService;
    private final BusinessClock businessClock;

    @Transactional
    public CreditPaymentReversal reverse(Long pumpId, Long paymentId, ReversePaymentRequest request, User currentUser) {
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_REVERSAL_REASON_REQUIRED, Map.of(),
                    "A reason is required to reverse a payment.");
        }
        Long clientId = paymentRepository.findClientIdByIdAndPumpId(paymentId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_PAYMENT, "Credit payment not found"));
        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT, "Credit client not found"));
        creditAccountPolicyService.lockLimitHolder(client);

        CreditPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_PAYMENT, "Credit payment not found"));
        requireReversible(payment);
        LocalDate today = businessClock.today();
        closedDayGuard.requireOpen(pumpId, today);

        payment.setPaymentApprovalStatus(ExpenseApprovalStatus.REVERSED);
        paymentRepository.save(payment);
        BigDecimal bounceCharge = request.bounceCharge() == null
                ? null : request.bounceCharge().setScale(2, RoundingMode.HALF_UP);
        CreditPaymentReversal reversal = reversalRepository.save(CreditPaymentReversal.builder()
                .pumpId(pumpId)
                .clientId(payment.getClientId())
                .paymentId(paymentId)
                .amount(payment.getAmount())
                .bounceCharge(bounceCharge)
                .reversalDate(today)
                .reason(reason)
                .reversedByUserId(currentUser.getId())
                .build());
        interestService.recalculateChargedInterestFrom(payment.getClientId(), payment.getPaidAt(),
                "Recalculated after reversing payment dated " + payment.getPaidAt(), currentUser);

        auditService.log(pumpId, AuditAction.CREDIT_PAYMENT_REVERSED, "CreditPayment", paymentId.toString(),
                "Payment ₹" + payment.getAmount() + " (" + payment.getPaymentMode() + ", dated " + payment.getPaidAt()
                        + ") for clientId=" + payment.getClientId() + " reversed on " + today
                        + (bounceCharge != null ? " with bounce charge ₹" + bounceCharge : "") + ", reason=" + reason,
                currentUser);
        notifyReversed(client, payment, reversal, currentUser);
        log.info("Credit payment reversed: pump={} paymentId={} client={} amount={} bounceCharge={} by={}",
                pumpId, paymentId, payment.getClientId(), payment.getAmount(), bounceCharge, currentUser.getId());
        return reversal;
    }

    private void requireReversible(CreditPayment payment) {
        if (payment.getPaymentApprovalStatus() == ExpenseApprovalStatus.REVERSED) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_ALREADY_REVERSED, Map.of(),
                    "This payment has already been reversed.");
        }
        if (payment.getPaymentApprovalStatus() != ExpenseApprovalStatus.APPROVED) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_NOT_REVERSIBLE,
                    Map.of("statusCode", payment.getPaymentApprovalStatus().name()),
                    "Only an approved payment can be reversed; this one is " + payment.getPaymentApprovalStatus()
                            + ". Reject a payment that is still waiting for approval.");
        }
        if (paymentRepository.isMatchedToBankLine(payment.getId())) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_MATCHED_TO_BANK_LINE, Map.of(),
                    "This payment is matched to a bank statement line. Unmatch it in bank reconciliation first.");
        }
    }

    private void notifyReversed(CreditClient client, CreditPayment payment, CreditPaymentReversal reversal,
                                User reversedBy) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("clientName", client.getName());
        params.put("amount", payment.getAmount().toPlainString());
        params.put("paidAt", payment.getPaidAt().toString());
        params.put("reversedByName", reversedBy.getFullName());
        params.put("reason", reversal.getReason());
        params.put("clientId", payment.getClientId());
        notificationDedupService.maybeInsert(reversal.getPumpId(), NotificationType.CREDIT_PAYMENT_REVERSED,
                "CREDIT_PAYMENT_REVERSED:client:" + payment.getClientId() + ":payment:" + payment.getId(),
                "Credit Payment Reversed",
                reversedBy.getFullName() + " reversed " + client.getName() + "'s payment of ₹" + payment.getAmount()
                        + " dated " + payment.getPaidAt() + ": " + reversal.getReason(),
                params);
    }
}
