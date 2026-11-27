package com.ppms.approval;

import com.ppms.adjustment.NozzleAdjustmentService;
import com.ppms.adjustment.NozzleReadingAdjustment;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.text.CodedText;
import com.ppms.common.text.ServerTextCodes;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditPayment;
import com.ppms.credit.CreditPaymentApprovalService;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.expense.ExpenseApprovalService;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.expense.PumpExpense;
import com.ppms.expense.PumpExpenseRepository;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Expenses, credit payments and managers' forward meter adjustments waiting for an Owner or Admin, in one list. A bulk decision runs every item through
 * its own approval service, each in its own transaction, so one refused item does not undo the others.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalQueueService {

    private static final CodedText OWN_PAYMENT_REASON = CodedText.of(ServerTextCodes.APPROVAL_OWN_PAYMENT,
            "You recorded this payment, so another Owner or Admin must decide it.");
    private static final String UNEXPECTED_FAILURE_MESSAGE = "Could not be saved. Try this item again.";

    private final PumpExpenseRepository expenseRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditClientRepository clientRepository;
    private final UserRepository userRepository;
    private final ExpenseApprovalService expenseApprovalService;
    private final CreditPaymentApprovalService paymentApprovalService;
    private final NozzleAdjustmentService meterAdjustmentService;
    private final NozzleRepository nozzleRepository;
    private final DispensaryUnitRepository duRepository;

    @Transactional(readOnly = true)
    public List<ApprovalItemResponse> pendingItems(Long pumpId, User currentUser) {
        List<PumpExpense> expenses = expenseRepository.findByPumpIdAndApprovalStatusOrderByCreatedAtAsc(
                pumpId, ExpenseApprovalStatus.PENDING_APPROVAL);
        List<CreditPayment> payments = paymentRepository.findPendingApprovalByPumpId(pumpId);
        List<NozzleReadingAdjustment> adjustments = meterAdjustmentService.pendingAdjustments(pumpId);

        Set<Long> userIds = new HashSet<>();
        expenses.forEach(expense -> userIds.add(submitterOf(expense)));
        payments.forEach(payment -> userIds.add(payment.getRecordedById()));
        adjustments.forEach(adjustment -> userIds.add(adjustment.getRecordedByUserId()));
        Map<Long, String> userNameById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        Map<Long, String> clientNameById = clientRepository.findAllById(
                        payments.stream().map(CreditPayment::getClientId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));

        List<ApprovalItemResponse> items = new ArrayList<>();
        for (PumpExpense expense : expenses) {
            items.add(new ApprovalItemResponse(ApprovalKind.EXPENSE, expense.getId(), expense.getAmount(),
                    expense.getExpenseDate(), userNameById.get(submitterOf(expense)),
                    expense.getSubmittedAt() != null ? expense.getSubmittedAt() : expense.getCreatedAt(),
                    expense.getCategory().name(), null, null, expense.getDescription(), null, null, Map.of(), null, null));
        }
        for (CreditPayment payment : payments) {
            boolean ownPayment = payment.getRecordedById().equals(currentUser.getId()) && !payment.isPostDatedCheque();
            items.add(new ApprovalItemResponse(ApprovalKind.CREDIT_PAYMENT, payment.getId(), payment.getAmount(),
                    payment.getPaidAt(), userNameById.get(payment.getRecordedById()), payment.getCreatedAt(),
                    null, payment.getClientId(), clientNameById.get(payment.getClientId()), payment.getNotes(),
                    ownPayment ? OWN_PAYMENT_REASON.text() : null,
                    ownPayment ? OWN_PAYMENT_REASON.code() : null,
                    ownPayment ? OWN_PAYMENT_REASON.params() : Map.of(), null, null));
        }
        items.addAll(meterAdjustmentItems(adjustments, userNameById));
        items.sort(Comparator.comparing(ApprovalItemResponse::submittedAt,
                Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())));
        return items;
    }

    public ApprovalDecisionResult decide(Long pumpId, ApprovalDecisionRequest request, User reviewer) {
        List<ApprovalDecisionResult.ItemResult> results = new ArrayList<>();
        for (ApprovalDecisionRequest.ApprovalItemKey item : request.items()) {
            results.add(decideOne(pumpId, item, request.action(), request.reason(), reviewer));
        }
        ApprovalDecisionResult result = ApprovalDecisionResult.of(results);
        log.info("Approval queue decision: pump={} action={} items={} succeeded={} failed={} by={}",
                pumpId, request.action(), results.size(), result.succeeded(), result.failed(), reviewer.getId());
        return result;
    }

    private ApprovalDecisionResult.ItemResult decideOne(Long pumpId, ApprovalDecisionRequest.ApprovalItemKey item,
                                                        ExpenseApprovalStatus action, String reason, User reviewer) {
        try {
            switch (item.kind()) {
                case EXPENSE -> expenseApprovalService.review(pumpId, item.id(), action, reason, reviewer);
                case CREDIT_PAYMENT -> paymentApprovalService.review(pumpId, item.id(), action, reason, reviewer);
                case METER_ADJUSTMENT -> meterAdjustmentService.review(pumpId, item.id(), action, reason, reviewer);
            }
            return new ApprovalDecisionResult.ItemResult(item.kind(), item.id(), true, null, null, null);
        } catch (BusinessException | ResourceNotFoundException e) {
            log.warn("Approval queue item refused: pump={} kind={} id={} action={}: {}",
                    pumpId, item.kind(), item.id(), action, e.getMessage());
            return new ApprovalDecisionResult.ItemResult(item.kind(), item.id(), false, e.getMessage(),
                    e.getCode(), e.getParams());
        } catch (RuntimeException e) {
            log.error("Approval queue item failed: pump={} kind={} id={} action={}",
                    pumpId, item.kind(), item.id(), action, e);
            return new ApprovalDecisionResult.ItemResult(item.kind(), item.id(), false, UNEXPECTED_FAILURE_MESSAGE,
                    ErrorCodes.APPROVAL_ITEM_NOT_SAVED, Map.of());
        }
    }

    private List<ApprovalItemResponse> meterAdjustmentItems(List<NozzleReadingAdjustment> adjustments,
                                                            Map<Long, String> userNameById) {
        if (adjustments.isEmpty()) {
            return List.of();
        }
        Map<Long, String> labelByNozzleId = nozzleLabels(adjustments.stream()
                .map(NozzleReadingAdjustment::getNozzleId).collect(Collectors.toSet()));
        return adjustments.stream()
                .map(adjustment -> new ApprovalItemResponse(ApprovalKind.METER_ADJUSTMENT, adjustment.getId(),
                        adjustment.getUnbilledValue(), adjustment.getCreatedAt().toLocalDate(),
                        userNameById.get(adjustment.getRecordedByUserId()), adjustment.getCreatedAt(),
                        adjustment.getFuelType(), null, null, adjustment.getReason(), null, null, Map.of(),
                        adjustment.getUnbilledLitres(), labelByNozzleId.get(adjustment.getNozzleId())))
                .toList();
    }

    private Map<Long, String> nozzleLabels(Set<Long> nozzleIds) {
        List<Nozzle> nozzles = nozzleRepository.findAllById(nozzleIds);
        Map<Long, Integer> duNumberById = duRepository.findAllById(nozzles.stream().map(Nozzle::getDuId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(DispensaryUnit::getId, DispensaryUnit::getDuNumber));
        return nozzles.stream().collect(Collectors.toMap(Nozzle::getId,
                nozzle -> "DU " + duNumberById.get(nozzle.getDuId()) + " · Nozzle " + nozzle.getNozzleNumber()));
    }

    private static Long submitterOf(PumpExpense expense) {
        return expense.getSubmittedByUserId() != null ? expense.getSubmittedByUserId() : expense.getRecordedByUserId();
    }
}
