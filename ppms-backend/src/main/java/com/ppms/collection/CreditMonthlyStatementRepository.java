package com.ppms.collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CreditMonthlyStatementRepository extends JpaRepository<CreditMonthlyStatement, Long> {

    boolean existsByClientIdAndPeriodFrom(Long clientId, LocalDate periodFrom);

    Optional<CreditMonthlyStatement> findByIdAndPumpId(Long id, Long pumpId);

    /** The list columns only; the stored page is read one statement at a time. */
    @Query("""
            SELECT new com.ppms.collection.MonthlyStatementResponse(s.id, s.clientId, c.name, s.periodFrom, s.periodTo,
                   s.closingBalance, s.transactionCount, s.generatedAt)
            FROM CreditMonthlyStatement s JOIN CreditClient c ON c.id = s.clientId
            WHERE s.pumpId = :pumpId AND s.periodFrom = :periodFrom
            ORDER BY c.name
            """)
    List<MonthlyStatementResponse> findListOfPumpMonth(Long pumpId, LocalDate periodFrom);

    @Query("""
            SELECT new com.ppms.collection.MonthlyStatementResponse(s.id, s.clientId, c.name, s.periodFrom, s.periodTo,
                   s.closingBalance, s.transactionCount, s.generatedAt)
            FROM CreditMonthlyStatement s JOIN CreditClient c ON c.id = s.clientId
            WHERE s.pumpId = :pumpId AND s.clientId = :clientId
            ORDER BY s.periodFrom DESC
            """)
    List<MonthlyStatementResponse> findListOfClient(Long pumpId, Long clientId);
}
