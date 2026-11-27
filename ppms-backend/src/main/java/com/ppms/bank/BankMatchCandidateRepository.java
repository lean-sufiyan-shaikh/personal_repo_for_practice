package com.ppms.bank;

import com.ppms.cash.BankDeposit;
import com.ppms.credit.CreditPayment;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.settlement.PaymentSettlement;
import com.ppms.supplier.FuelSupplierPayment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Records of a pump dated within a window that no statement line holds in one of {@code claimingStatuses}:
 * what a statement line can still be matched to.
 */
public interface BankMatchCandidateRepository extends Repository<BankStatementLine, Long> {

    @Query("""
            SELECT ps FROM PaymentSettlement ps
            WHERE ps.pumpId = :pumpId AND ps.voidStatus = 'ACTIVE' AND ps.settlementDate BETWEEN :from AND :to
              AND NOT EXISTS (SELECT l.id FROM BankStatementLine l
                              WHERE l.matchedSettlementId = ps.id AND l.matchStatus IN :claimingStatuses)
            ORDER BY ps.settlementDate, ps.id
            """)
    List<PaymentSettlement> findOpenSettlements(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                                @Param("to") LocalDate to,
                                                @Param("claimingStatuses") Collection<BankLineMatchStatus> claimingStatuses);

    @Query("""
            SELECT d FROM BankDeposit d
            WHERE d.pumpId = :pumpId AND d.depositDate BETWEEN :from AND :to
              AND NOT EXISTS (SELECT l.id FROM BankStatementLine l
                              WHERE l.matchedDepositId = d.id AND l.matchStatus IN :claimingStatuses)
            ORDER BY d.depositDate, d.id
            """)
    List<BankDeposit> findOpenDeposits(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       @Param("claimingStatuses") Collection<BankLineMatchStatus> claimingStatuses);

    @Query("""
            SELECT p FROM FuelSupplierPayment p
            WHERE p.pumpId = :pumpId AND p.paymentDate BETWEEN :from AND :to
              AND NOT EXISTS (SELECT l.id FROM BankStatementLine l
                              WHERE l.matchedSupplierPaymentId = p.id AND l.matchStatus IN :claimingStatuses)
            ORDER BY p.paymentDate, p.id
            """)
    List<FuelSupplierPayment> findOpenSupplierPayments(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                                       @Param("to") LocalDate to,
                                                       @Param("claimingStatuses") Collection<BankLineMatchStatus> claimingStatuses);

    @Query("""
            SELECT p FROM CreditPayment p
            WHERE p.pumpId = :pumpId AND p.paidAt BETWEEN :from AND :to AND p.paymentApprovalStatus = :approved
              AND NOT EXISTS (SELECT l.id FROM BankStatementLine l
                              WHERE l.matchedPaymentId = p.id AND l.matchStatus IN :claimingStatuses)
            ORDER BY p.paidAt, p.id
            """)
    List<CreditPayment> findOpenCreditPayments(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                               @Param("to") LocalDate to,
                                               @Param("approved") ExpenseApprovalStatus approved,
                                               @Param("claimingStatuses") Collection<BankLineMatchStatus> claimingStatuses);
}
