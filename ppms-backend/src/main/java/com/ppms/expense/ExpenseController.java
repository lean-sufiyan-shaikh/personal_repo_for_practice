package com.ppms.expense;

import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.idempotency.IdempotencyService;
import com.ppms.common.idempotency.IdempotentRequest;
import com.ppms.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.RoundingMode;
import java.util.Map;

@RestController
@RequestMapping("/api/pumps")
@RequiredArgsConstructor
public class ExpenseController {

    private final PumpExpenseRepository  expenseRepository;
    private final ExpenseService         expenseService;
    private final ExpenseApprovalService expenseApprovalService;
    private final IdempotencyService     idempotencyService;

    /**
     * GET /api/pumps/{pumpId}/expenses?page=0&size=50&category=MAINTENANCE&approvalStatus=APPROVED
     * Returns paginated expenses for this pump, newest first.
     * category and approvalStatus are optional filters — omit to return all.
     * Accessible by OWNER, ADMIN, and ACCOUNTANT (read-only financial access).
     *
     * Note: nullable enum params cause PostgreSQL type inference errors in JPQL,
     * so filter dispatch is done in Java rather than a single nullable-param query.
     */
    @GetMapping("/{pumpId}/expenses")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR', 'ACCOUNTANT')")
    public PagedResponse<PumpExpense> getExpenses(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) ExpenseCategory category,
            @RequestParam(required = false) ExpenseApprovalStatus approvalStatus,
            @AuthenticationPrincipal User currentUser) {
        var pageReq = PageRequests.of(page, size);
        var result = (category != null && approvalStatus != null)
                ? expenseRepository.findByPumpIdAndCategoryAndApprovalStatusOrderByExpenseDateDescCreatedAtDesc(pumpId, category, approvalStatus, pageReq)
                : (category != null)
                ? expenseRepository.findByPumpIdAndCategoryOrderByExpenseDateDescCreatedAtDesc(pumpId, category, pageReq)
                : (approvalStatus != null)
                ? expenseRepository.findByPumpIdAndApprovalStatusOrderByExpenseDateDescCreatedAtDesc(pumpId, approvalStatus, pageReq)
                : expenseRepository.findByPumpIdOrderByExpenseDateDescCreatedAtDesc(pumpId, pageReq);
        return PagedResponse.of(result);
    }

    /**
     * GET /api/pumps/{pumpId}/expenses/summary
     * Approved total and pending-approval count/total across every expense of the pump (not one page).
     */
    @GetMapping("/{pumpId}/expenses/summary")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR', 'ACCOUNTANT')")
    public ExpenseSummaryResponse getExpenseSummary(@PathVariable Long pumpId) {
        return new ExpenseSummaryResponse(
                expenseRepository.sumAmountByPumpIdAndApprovalStatus(pumpId, ExpenseApprovalStatus.APPROVED)
                        .setScale(2, RoundingMode.HALF_UP),
                expenseRepository.countByPumpIdAndApprovalStatus(pumpId, ExpenseApprovalStatus.PENDING_APPROVAL),
                expenseRepository.sumAmountByPumpIdAndApprovalStatus(pumpId, ExpenseApprovalStatus.PENDING_APPROVAL)
                        .setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * POST /api/pumps/{pumpId}/expenses
     * Records an expense with where it was paid from. saveDraft=true keeps it as a DRAFT until submitted; otherwise
     * OWNER/ADMIN entries and amounts within the pump's threshold are approved at once and move their cash.
     * An Idempotency-Key header makes a resend after a timeout return the first expense instead of recording two.
     */
    @PostMapping("/{pumpId}/expenses")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<?> createExpense(
            @PathVariable Long pumpId,
            @Valid @RequestBody CreateExpenseRequest req,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal User currentUser) {
        return idempotencyService.runOnce(
                IdempotentRequest.of(currentUser.getId(), idempotencyKey, httpRequest, req),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(expenseService.create(pumpId, req, currentUser)));
    }

    /**
     * PATCH /api/pumps/{pumpId}/expenses/{expenseId}/submit
     * Submits a DRAFT expense, applying the same approval rules as at creation.
     */
    @PatchMapping("/{pumpId}/expenses/{expenseId}/submit")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public PumpExpense submitExpense(
            @PathVariable Long pumpId,
            @PathVariable Long expenseId,
            @AuthenticationPrincipal User currentUser) {
        return expenseService.submit(pumpId, expenseId, currentUser);
    }

    /**
     * POST /api/pumps/{pumpId}/expenses/{expenseId}/void
     * Voids an approved expense with a reason and reverses the cash it took from the safe or petty cash.
     */
    @PostMapping("/{pumpId}/expenses/{expenseId}/void")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public PumpExpense voidExpense(
            @PathVariable Long pumpId,
            @PathVariable Long expenseId,
            @Valid @RequestBody VoidExpenseRequest req,
            @AuthenticationPrincipal User currentUser) {
        return expenseService.voidExpense(pumpId, expenseId, req.reason(), currentUser);
    }

    /**
     * PATCH /api/pumps/{pumpId}/expenses/{expenseId}/approve
     * Owner or Admin approves or rejects a PENDING_APPROVAL expense.
     * Body: { "action": "APPROVED" | "REJECTED", "notes": "optional reason" }
     */
    @PatchMapping("/{pumpId}/expenses/{expenseId}/approve")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public PumpExpense reviewExpense(
            @PathVariable Long pumpId,
            @PathVariable Long expenseId,
            @RequestBody ApproveExpenseRequest req,
            @AuthenticationPrincipal User currentUser) {
        return expenseApprovalService.review(pumpId, expenseId, req.getAction(), req.getNotes(), currentUser);
    }

    /**
     * DELETE /api/pumps/{pumpId}/expenses/{expenseId}
     * Removes an expense entry. Only OWNER can delete.
     */
    @DeleteMapping("/{pumpId}/expenses/{expenseId}")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExpense(
            @PathVariable Long pumpId,
            @PathVariable Long expenseId,
            @AuthenticationPrincipal User currentUser) {
        PumpExpense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.EXPENSE, "Expense not found"));
        if (!expense.getPumpId().equals(pumpId)) {
            throw new ResourceNotFoundException(EntityType.EXPENSE, "Expense not found");
        }
        if (expense.getApprovalStatus() != ExpenseApprovalStatus.DRAFT) {
            throw new BusinessException(ErrorCodes.EXPENSE_DELETE_NOT_DRAFT, Map.of(),
                    "Only DRAFT expenses can be deleted. Submitted or reviewed expenses are permanent records.");
        }
        expenseRepository.delete(expense);
    }

}
