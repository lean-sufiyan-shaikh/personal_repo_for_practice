package com.ppms.approval;

import com.ppms.adjustment.NozzleAdjustmentService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditPaymentApprovalService;
import com.ppms.expense.ExpenseApprovalService;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ApprovalQueueServiceTest {

    private static final long PUMP_ID = 4L;

    @Mock private ExpenseApprovalService expenseApprovalService;
    @Mock private CreditPaymentApprovalService paymentApprovalService;
    @Mock private NozzleAdjustmentService meterAdjustmentService;
    @InjectMocks private ApprovalQueueService queueService;

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();

    @Test
    void eachItemIsDecidedOnItsOwnAndFailuresSayWhy() {
        lenient().doThrow(new BusinessException("EXPENSE_NOT_PENDING", Map.of(), "Expense is not pending approval"))
                .when(expenseApprovalService).review(PUMP_ID, 2L, ExpenseApprovalStatus.APPROVED, null, owner);
        lenient().doThrow(new ResourceNotFoundException(EntityType.EXPENSE, "Expense not found"))
                .when(expenseApprovalService).review(PUMP_ID, 3L, ExpenseApprovalStatus.APPROVED, null, owner);
        lenient().doThrow(new IllegalStateException("database hiccup"))
                .when(paymentApprovalService).review(PUMP_ID, 5L, ExpenseApprovalStatus.APPROVED, null, owner);

        ApprovalDecisionResult result = queueService.decide(PUMP_ID, new ApprovalDecisionRequest(List.of(
                item(ApprovalKind.EXPENSE, 1L), item(ApprovalKind.EXPENSE, 2L), item(ApprovalKind.EXPENSE, 3L),
                item(ApprovalKind.CREDIT_PAYMENT, 4L), item(ApprovalKind.CREDIT_PAYMENT, 5L)),
                ExpenseApprovalStatus.APPROVED, null), owner);

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.results())
                .extracting(ApprovalDecisionResult.ItemResult::id, ApprovalDecisionResult.ItemResult::succeeded,
                        ApprovalDecisionResult.ItemResult::message)
                .containsExactly(tuple(1L, true, null),
                        tuple(2L, false, "Expense is not pending approval"),
                        tuple(3L, false, "Expense not found"),
                        tuple(4L, true, null),
                        tuple(5L, false, "Could not be saved. Try this item again."));
        assertThat(result.results().get(4).code()).isEqualTo("APPROVAL_ITEM_NOT_SAVED");
    }

    @Test
    void rejectingNeedsAReason() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        List<ApprovalDecisionRequest.ApprovalItemKey> items = List.of(item(ApprovalKind.EXPENSE, 1L));

        assertThat(validator.validate(new ApprovalDecisionRequest(items, ExpenseApprovalStatus.REJECTED, null))).hasSize(1);
        assertThat(validator.validate(new ApprovalDecisionRequest(items, ExpenseApprovalStatus.REJECTED, "  "))).hasSize(1);
        assertThat(validator.validate(new ApprovalDecisionRequest(items, ExpenseApprovalStatus.REJECTED, "No bill attached")))
                .isEmpty();
    }

    private static ApprovalDecisionRequest.ApprovalItemKey item(ApprovalKind kind, long id) {
        return new ApprovalDecisionRequest.ApprovalItemKey(kind, id);
    }
}
