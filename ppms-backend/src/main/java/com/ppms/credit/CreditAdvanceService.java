package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Pays back part of what a client paid ahead of its bills. The advance is held on the main account, so a refund is
 * booked there whichever account of the group it is asked for; a cash refund leaves the safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditAdvanceService {

    private final CreditClientAccessService clientAccessService;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditInterestService interestService;
    private final CreditAdjustmentRepository adjustmentRepository;
    private final CashDrawerService cashDrawerService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final BalanceSheetStalenessService balanceSheetStalenessService;

    @Transactional
    public CreditAdjustment refundAdvance(Long pumpId, Long clientId, MoneyMovementRequest request, User refundedBy) {
        CreditClient holder = accountPolicyService.lockLimitHolder(clientAccessService.requireClientForPump(pumpId, clientId));
        String reason = CreditDeskRules.requiredReason(request.reason());
        PaymentMode mode = requiredMode(request.paymentMode());
        BigDecimal amount = CreditDeskRules.money(request.amount());
        LocalDate today = businessClock.today();
        closedDayGuard.requireOpen(pumpId, today);
        BigDecimal advance = CreditLedgerQueryService.advanceOf(interestService.computeOutstandingWithChildren(holder.getId()));
        CreditDeskRules.requireAtMost(amount, advance, ErrorCodes.CREDIT_ADVANCE_REFUND_EXCEEDS_ADVANCE,
                "A refund can be at most the advance the client has paid");

        Long cashEventId = mode == PaymentMode.CASH
                ? cashDrawerService.recordAutomaticCashOut(pumpId, amount, today,
                        "Advance refunded to " + holder.getName(), refundedBy).getId()
                : null;
        CreditAdjustment refund = adjustmentRepository.save(CreditAdjustment.builder()
                .pumpId(pumpId)
                .clientId(holder.getId())
                .kind(CreditAdjustmentKind.ADVANCE_REFUND)
                .amount(amount)
                .adjustmentDate(today)
                .reason(reason)
                .refundMode(mode)
                .refundReference(CreditPaymentReferenceCheck.trimmedOrNull(request.referenceNo()))
                .cashEventId(cashEventId)
                .createdBy(refundedBy.getId())
                .build());
        interestService.recalculateChargedInterestFrom(holder.getId(), today,
                "Recalculated after an advance refund dated " + today, refundedBy);
        balanceSheetStalenessService.markDaySheetsStale(pumpId, List.of(today),
                StaleReasons.creditAccountChanged(holder.getId()));
        auditService.log(pumpId, AuditAction.CREDIT_ADVANCE_REFUNDED, "CreditAdjustment", refund.getId().toString(),
                "Advance ₹" + amount + " refunded to clientId=" + holder.getId() + " by " + mode + ", reason=" + reason,
                refundedBy);
        log.info("Advance refunded: pump={} client={} amount={} mode={} by={}", pumpId, holder.getId(), amount, mode,
                refundedBy.getId());
        return refund;
    }

    static PaymentMode requiredMode(PaymentMode mode) {
        if (mode == null) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_MODE_REQUIRED, Map.of(), "Choose how the money was paid.");
        }
        return mode;
    }
}
