package com.ppms.settlement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SettlementChargebackRepository extends JpaRepository<SettlementChargeback, Long> {

    interface SettlementTotal {
        Long getSettlementId();

        BigDecimal getAmount();
    }

    Page<SettlementChargeback> findByPumpIdOrderByChargebackDateDescIdDesc(Long pumpId, Pageable pageable);

    List<SettlementChargeback> findByPumpIdAndChargebackDateOrderByIdAsc(Long pumpId, LocalDate chargebackDate);

    @Query("""
            SELECT COALESCE(SUM(c.amount), 0) FROM SettlementChargeback c
            WHERE c.pumpId = :pumpId AND c.chargebackDate BETWEEN :from AND :to
            """)
    BigDecimal sumAmountBetween(@Param("pumpId") Long pumpId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM SettlementChargeback c WHERE c.settlementId = :settlementId")
    BigDecimal sumAmountBySettlementId(@Param("settlementId") Long settlementId);

    @Query("""
            SELECT c.settlementId AS settlementId, SUM(c.amount) AS amount FROM SettlementChargeback c
            WHERE c.settlementId IN :settlementIds GROUP BY c.settlementId
            """)
    List<SettlementTotal> sumAmountBySettlementIds(@Param("settlementIds") Collection<Long> settlementIds);

    @Query("""
            SELECT COUNT(c) > 0 FROM SettlementChargeback c
            WHERE c.pumpId = :pumpId AND LOWER(TRIM(c.reference)) = LOWER(TRIM(:reference))
            """)
    boolean existsWithReference(@Param("pumpId") Long pumpId, @Param("reference") String reference);
}
