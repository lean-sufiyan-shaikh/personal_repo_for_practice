package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface CreditAdjustmentRepository extends JpaRepository<CreditAdjustment, Long> {

    List<CreditAdjustment> findByClientIdIn(Collection<Long> clientIds);

    List<CreditAdjustment> findByClientIdOrderByCreatedAtDesc(Long clientId);

    /** Rows of [clientId, signed total]; a total below zero lowers what the client owes. */
    @Query("SELECT a.clientId, SUM(CASE WHEN a.kind = com.ppms.credit.CreditAdjustmentKind.ADVANCE_REFUND "
            + "THEN a.amount ELSE -a.amount END) FROM CreditAdjustment a WHERE a.clientId IN :clientIds GROUP BY a.clientId")
    List<Object[]> signedSumsByClientIds(@Param("clientIds") Collection<Long> clientIds);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM CreditAdjustment a "
            + "WHERE a.kind = com.ppms.credit.CreditAdjustmentKind.CREDIT_NOTE "
            + "AND a.debitType = :debitType AND a.debitId = :debitId")
    BigDecimal sumCreditedOnDebit(@Param("debitType") CreditDebitType debitType, @Param("debitId") Long debitId);

    @Query("SELECT COUNT(a) > 0 FROM CreditAdjustment a WHERE a.kind = com.ppms.credit.CreditAdjustmentKind.CREDIT_NOTE "
            + "AND a.debitType = :debitType AND a.debitId IN :debitIds")
    boolean existsCreditNoteOnDebits(@Param("debitType") CreditDebitType debitType,
                                     @Param("debitIds") Collection<Long> debitIds);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM CreditAdjustment a WHERE a.pumpId = :pumpId "
            + "AND a.kind = com.ppms.credit.CreditAdjustmentKind.WRITE_OFF AND a.adjustmentDate BETWEEN :from AND :to")
    BigDecimal sumWrittenOffBetween(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);
}
