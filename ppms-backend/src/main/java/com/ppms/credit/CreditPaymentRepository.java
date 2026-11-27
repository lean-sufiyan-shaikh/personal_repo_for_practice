package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditPaymentRepository extends JpaRepository<CreditPayment, Long> {

    // Returns all payments for a client, newest first — used for the ledger transaction list
    List<CreditPayment> findByClientIdOrderByPaidAtDesc(Long clientId);

    /**
     * Total APPROVED payments made by a client.
     * Excludes PENDING_APPROVAL and REJECTED payments so they don't reduce the outstanding balance
     * until explicitly approved by Owner/Admin.
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM CreditPayment p WHERE p.clientId = :clientId AND p.paymentApprovalStatus = 'APPROVED'")
    BigDecimal sumAmountByClientId(@Param("clientId") Long clientId);

    /**
     * Credit recovered by a pump on a date, for the day's figures: payments approved for that date, reversed ones
     * included, less the payments reversed on that date. A reversal changes the day it is made, not the day paid.
     */
    @Query(value = "SELECT COALESCE((SELECT SUM(p.amount) FROM credit_payments p WHERE p.pump_id = :pumpId"
            + " AND p.payment_date = :date AND p.payment_approval_status IN ('APPROVED', 'REVERSED')), 0)"
            + " - COALESCE((SELECT SUM(r.amount) FROM credit_payment_reversals r WHERE r.pump_id = :pumpId"
            + " AND r.reversal_date = :date), 0)", nativeQuery = true)
    BigDecimal sumAmountByPumpIdAndDate(@Param("pumpId") Long pumpId, @Param("date") LocalDate date);

    /** Rows of [clientId, latest APPROVED payment date]. */
    @Query("SELECT p.clientId, MAX(p.paidAt) FROM CreditPayment p WHERE p.clientId IN :clientIds AND p.paymentApprovalStatus = 'APPROVED' GROUP BY p.clientId")
    List<Object[]> findLastPaymentDatesByClientIds(@Param("clientIds") java.util.Collection<Long> clientIds);

    /**
     * Batch sum of APPROVED payments per client.
     * Used for efficient outstanding balance calculation across all clients.
     * Excludes PENDING_APPROVAL and REJECTED so they don't affect balances.
     */
    @Query("SELECT p.clientId, COALESCE(SUM(p.amount), 0) FROM CreditPayment p WHERE p.clientId IN :clientIds AND p.paymentApprovalStatus = 'APPROVED' GROUP BY p.clientId")
    List<Object[]> sumAmountsByClientIds(@Param("clientIds") java.util.Collection<Long> clientIds);

    /** All APPROVED payments for a set of clients — used for chronological interest-first allocation simulation. */
    @Query("SELECT p FROM CreditPayment p WHERE p.clientId IN :clientIds AND p.paymentApprovalStatus = 'APPROVED'")
    List<CreditPayment> findApprovedByClientIdIn(@Param("clientIds") java.util.Collection<Long> clientIds);

    /** Approved payments and payments awaiting approval of the given clients, dated on or after {@code fromDate}. */
    @Query("SELECT p FROM CreditPayment p WHERE p.clientId IN :clientIds"
            + " AND p.paymentApprovalStatus IN ('APPROVED', 'PENDING_APPROVAL') AND p.paidAt >= :fromDate")
    List<CreditPayment> findApprovedOrPendingByClientIdInPaidFrom(@Param("clientIds") java.util.Collection<Long> clientIds,
                                                         @Param("fromDate") LocalDate fromDate);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM CreditPayment p WHERE p.pumpId = :pumpId AND p.paymentApprovalStatus = 'APPROVED' AND p.paidAt BETWEEN :fromDate AND :toDate")
    BigDecimal sumApprovedByPumpIdBetween(@Param("pumpId") Long pumpId, @Param("fromDate") LocalDate fromDate,
                                          @Param("toDate") LocalDate toDate);

    /** Total PENDING_APPROVAL payments across the given clients — reserved against outstanding so pending payments cannot over-pay. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM CreditPayment p WHERE p.clientId IN :clientIds AND p.paymentApprovalStatus = 'PENDING_APPROVAL'")
    BigDecimal sumPendingAmountByClientIds(@Param("clientIds") java.util.Collection<Long> clientIds);

    @Query("SELECT p.clientId FROM CreditPayment p WHERE p.id = :id AND p.pumpId = :pumpId")
    Optional<Long> findClientIdByIdAndPumpId(@Param("id") Long id, @Param("pumpId") Long pumpId);

    /** Returns all payments pending approval for a pump — for Owner/Admin approval queue. */
    @Query("SELECT p FROM CreditPayment p WHERE p.pumpId = :pumpId AND p.paymentApprovalStatus = 'PENDING_APPROVAL' ORDER BY p.createdAt ASC")
    List<CreditPayment> findPendingApprovalByPumpId(@Param("pumpId") Long pumpId);

    /** Payments of the pump with the same reference, ignoring case, dated on or after fromDate and not rejected; newest first. */
    @Query("SELECT p FROM CreditPayment p WHERE p.pumpId = :pumpId AND LOWER(p.referenceNo) = LOWER(:referenceNo)"
            + " AND p.paidAt >= :fromDate AND p.paymentApprovalStatus <> 'REJECTED' ORDER BY p.paidAt DESC, p.id DESC")
    List<CreditPayment> findSameReferenceSince(@Param("pumpId") Long pumpId, @Param("referenceNo") String referenceNo,
                                               @Param("fromDate") LocalDate fromDate);

    @Query("SELECT COUNT(l) > 0 FROM BankStatementLine l WHERE l.matchedPaymentId = :paymentId"
            + " AND l.matchStatus = 'MATCHED'")
    boolean isMatchedToBankLine(@Param("paymentId") Long paymentId);

    boolean existsByClientId(Long clientId);

    boolean existsByIdAndPumpId(Long id, Long pumpId);
}
