package com.ppms.expense;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Records, submits and voids expenses. An expense moves its cash (safe or petty cash) when it becomes approved; an
 * approved expense is never edited but voided by an owner or admin, which reverses that cash, and recorded again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final String ENTITY_NAME = "PumpExpense";

    private final PumpExpenseRepository expenseRepository;
    private final ApprovalPolicy approvalPolicy;
    private final ExpenseCashMovements cashMovements;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final ClosedDayGuard closedDayGuard;
    private final BalanceSheetStalenessService balanceSheetStalenessService;

    /** A draft waits for submit; otherwise owners and admins, or amounts within the threshold, are approved at once. */
    @Transactional
    public PumpExpense create(Long pumpId, CreateExpenseRequest req, User currentUser) {
        requireNotInFuture(req.getExpenseDate());
        closedDayGuard.requireOpen(pumpId, req.getExpenseDate());
        BigDecimal amount = req.getAmount().setScale(2, RoundingMode.HALF_UP);
        ExpenseApprovalStatus status = req.isSaveDraft()
                ? ExpenseApprovalStatus.DRAFT
                : approvalPolicy.resolveExpenseStatus(pumpId, currentUser, amount, req.getCategory(), req.getExpenseDate());

        PumpExpense expense = expenseRepository.save(PumpExpense.builder()
                .pumpId(pumpId)
                .category(req.getCategory())
                .amount(amount)
                .description(req.getDescription().trim())
                .expenseDate(req.getExpenseDate())
                .paidFrom(req.getPaidFrom())
                .recordedByUserId(currentUser.getId())
                .approvalStatus(status)
                .submittedByUserId(req.isSaveDraft() ? null : currentUser.getId())
                .submittedAt(req.isSaveDraft() ? null : businessClock.now())
                .build());
        if (status == ExpenseApprovalStatus.APPROVED) {
            cashMovements.payOut(expense, currentUser);
            expense = expenseRepository.save(expense);
        }
        if (status != ExpenseApprovalStatus.DRAFT) {
            balanceSheetStalenessService.markDaySheetsStale(pumpId, List.of(expense.getExpenseDate()),
                    StaleReasons.expenseChanged(expense.getId()));
        }
        auditService.log(pumpId, AuditAction.EXPENSE_RECORDED, ENTITY_NAME, expense.getId().toString(),
                req.getCategory() + " expense ₹" + amount + " paid from " + req.getPaidFrom() + " recorded: "
                        + expense.getDescription() + " (status: " + status + ")", currentUser);
        log.info("Expense recorded: pump={}, expense={}, amount={}, paidFrom={}, status={}, by={}", pumpId,
                expense.getId(), amount, req.getPaidFrom(), status, currentUser.getId());
        return expense;
    }

    /** Only the person who recorded the draft or an owner or admin can submit it. */
    @Transactional
    public PumpExpense submit(Long pumpId, Long expenseId, User currentUser) {
        PumpExpense expense = findExpense(pumpId, expenseId);
        if (expense.getApprovalStatus() != ExpenseApprovalStatus.DRAFT) {
            throw new BusinessException(ErrorCodes.EXPENSE_SUBMIT_NOT_DRAFT,
                    Map.of("statusCode", expense.getApprovalStatus().name()),
                    "Only DRAFT expenses can be submitted (current status: " + expense.getApprovalStatus() + ")");
        }
        if (!isOwnerOrAdmin(currentUser) && !expense.getRecordedByUserId().equals(currentUser.getId())) {
            throw new BusinessException(ErrorCodes.EXPENSE_SUBMIT_NOT_ALLOWED, Map.of(),
                    "Only the expense creator or an Owner/Admin can submit this draft.");
        }
        requireNotInFuture(expense.getExpenseDate());
        closedDayGuard.requireOpen(pumpId, expense.getExpenseDate());
        ExpenseApprovalStatus status = approvalPolicy.resolveExpenseStatus(pumpId, currentUser, expense.getAmount(),
                expense.getCategory(), expense.getExpenseDate());
        expense.setApprovalStatus(status);
        expense.setSubmittedByUserId(currentUser.getId());
        expense.setSubmittedAt(businessClock.now());
        if (status == ExpenseApprovalStatus.APPROVED) {
            cashMovements.payOut(expense, currentUser);
        }
        balanceSheetStalenessService.markDaySheetsStale(pumpId, List.of(expense.getExpenseDate()),
                StaleReasons.expenseChanged(expenseId));
        return expenseRepository.save(expense);
    }

    /** The pump row is locked so two voids of the same expense cannot both reverse its cash. */
    @Transactional
    public PumpExpense voidExpense(Long pumpId, Long expenseId, String reason, User currentUser) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        PumpExpense expense = findExpense(pumpId, expenseId);
        if (expense.getApprovalStatus() != ExpenseApprovalStatus.APPROVED) {
            throw new BusinessException(ErrorCodes.EXPENSE_NOT_VOIDABLE,
                    Map.of("statusCode", expense.getApprovalStatus().name()),
                    "Only an approved expense can be voided (current status: " + expense.getApprovalStatus() + ").");
        }
        closedDayGuard.requireOpen(pumpId, expense.getExpenseDate());
        String trimmedReason = reason.trim();
        cashMovements.reverse(expense, trimmedReason, currentUser);
        expense.setApprovalStatus(ExpenseApprovalStatus.VOIDED);
        expense.setVoidReason(trimmedReason);
        expense.setVoidedByUserId(currentUser.getId());
        expense.setVoidedAt(businessClock.now());
        PumpExpense saved = expenseRepository.save(expense);
        balanceSheetStalenessService.markDaySheetsStale(pumpId, List.of(saved.getExpenseDate()),
                StaleReasons.expenseVoided(expenseId));
        auditService.log(pumpId, AuditAction.EXPENSE_VOIDED, ENTITY_NAME, expenseId.toString(),
                saved.getCategory() + " expense ₹" + saved.getAmount() + " of " + saved.getExpenseDate() + " paid from "
                        + saved.getPaidFrom() + " voided: " + trimmedReason, currentUser);
        log.info("Expense voided: pump={}, expense={}, amount={}, paidFrom={}, by={}", pumpId, expenseId,
                saved.getAmount(), saved.getPaidFrom(), currentUser.getId());
        return saved;
    }

    private void requireNotInFuture(LocalDate expenseDate) {
        if (expenseDate.isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.EXPENSE_DATE_IN_FUTURE, Map.of("date", expenseDate.toString()),
                    "An expense cannot be dated in the future.");
        }
    }

    private PumpExpense findExpense(Long pumpId, Long expenseId) {
        return expenseRepository.findById(expenseId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.EXPENSE, "Expense not found"));
    }

    private static boolean isOwnerOrAdmin(User user) {
        return user.getRole() == UserRole.OWNER || user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SUPER_ADMIN;
    }
}
