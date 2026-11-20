package com.ppms.expense;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public interface PumpExpenseRepository extends JpaRepository<PumpExpense, Long> {

    /** Expenses dated within [from, to], both days included — used by reports and CSV export. */
    List<PumpExpense> findByPumpIdAndExpenseDateBetweenOrderByExpenseDateDescCreatedAtDesc(
            Long pumpId, LocalDate from, LocalDate to);

    /** Paginated — use for the API. */
    Page<PumpExpense> findByPumpIdOrderByExpenseDateDescCreatedAtDesc(Long pumpId, Pageable pageable);

    /** Filter by category only. */
    Page<PumpExpense> findByPumpIdAndCategoryOrderByExpenseDateDescCreatedAtDesc(
            Long pumpId, ExpenseCategory category, Pageable pageable);

    /** Filter by approval status only. */
    Page<PumpExpense> findByPumpIdAndApprovalStatusOrderByExpenseDateDescCreatedAtDesc(
            Long pumpId, ExpenseApprovalStatus approvalStatus, Pageable pageable);

    /** Filter by both category and approval status. */
    Page<PumpExpense> findByPumpIdAndCategoryAndApprovalStatusOrderByExpenseDateDescCreatedAtDesc(
            Long pumpId, ExpenseCategory category, ExpenseApprovalStatus approvalStatus, Pageable pageable);

    /** Fetch approved expenses for a specific pump and date — used by balance sheet DAY reports. */
    List<PumpExpense> findByPumpIdAndExpenseDateAndApprovalStatus(
            Long pumpId, LocalDate expenseDate, ExpenseApprovalStatus approvalStatus);

    /** Approved expenses recorded within [from, to) — used by live DAY balance sheets. */
    List<PumpExpense> findByPumpIdAndApprovalStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long pumpId, ExpenseApprovalStatus approvalStatus, OffsetDateTime from, OffsetDateTime to);
}
