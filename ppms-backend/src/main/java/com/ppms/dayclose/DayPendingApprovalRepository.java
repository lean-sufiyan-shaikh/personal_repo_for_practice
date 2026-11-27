package com.ppms.dayclose;

import com.ppms.expense.PumpExpense;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Expenses and credit payments dated on one business day that still wait for an owner or admin's decision. */
public interface DayPendingApprovalRepository extends Repository<PumpExpense, Long> {

    interface PendingTotal {
        long getItemCount();

        BigDecimal getAmount();
    }

    @Query("""
            SELECT COUNT(e) AS itemCount, COALESCE(SUM(e.amount), 0) AS amount
            FROM PumpExpense e
            WHERE e.pumpId = :pumpId AND e.expenseDate = :date AND e.approvalStatus = 'PENDING_APPROVAL'
            """)
    PendingTotal pendingExpensesOn(@Param("pumpId") Long pumpId, @Param("date") LocalDate date);

    @Query("""
            SELECT COUNT(p) AS itemCount, COALESCE(SUM(p.amount), 0) AS amount
            FROM CreditPayment p
            WHERE p.pumpId = :pumpId AND p.paidAt = :date AND p.paymentApprovalStatus = 'PENDING_APPROVAL'
            """)
    PendingTotal pendingCreditPaymentsOn(@Param("pumpId") Long pumpId, @Param("date") LocalDate date);
}
