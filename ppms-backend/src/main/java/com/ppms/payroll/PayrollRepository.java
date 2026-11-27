package com.ppms.payroll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PayrollRepository extends JpaRepository<PayrollRecord, Long> {

    List<PayrollRecord> findByPumpIdOrderByPeriodFromDescCreatedAtDesc(Long pumpId);

    List<PayrollRecord> findByPumpIdAndUserIdOrderByPeriodFromDesc(Long pumpId, Long userId);

    Optional<PayrollRecord> findFirstByPumpIdAndUserIdAndPeriodFromLessThanEqualAndPeriodToGreaterThanEqual(
            Long pumpId, Long userId, LocalDate periodTo, LocalDate periodFrom);

    /**
     * A staff member's payroll ending before {@code periodFrom} whose deductions exceeded the pay and were not yet
     * carried into a later payroll.
     */
    @Query("""
            SELECT r FROM PayrollRecord r
            WHERE r.pumpId = :pumpId AND r.userId = :userId AND r.periodTo < :periodFrom
              AND r.uncoveredDeductions > 0 AND r.carriedToPayrollId IS NULL
            ORDER BY r.periodFrom ASC
            """)
    List<PayrollRecord> findWithUncarriedDeductionsBefore(@Param("pumpId") Long pumpId, @Param("userId") Long userId,
                                                          @Param("periodFrom") LocalDate periodFrom);

    /** Pay of the payroll paid on a date from {@code from} to {@code to}: net pay plus the advances it took back. */
    @Query("SELECT COALESCE(SUM(r.netPay + r.advanceRecovered), 0) FROM PayrollRecord r"
            + " WHERE r.pumpId = :pumpId AND r.paidDate BETWEEN :from AND :to")
    BigDecimal sumPayPaidBetween(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    List<PayrollRecord> findByPumpIdAndPaidDateOrderByIdAsc(Long pumpId, LocalDate paidDate);
}
