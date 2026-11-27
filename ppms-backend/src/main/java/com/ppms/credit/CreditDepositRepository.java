package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Repository
public interface CreditDepositRepository extends JpaRepository<CreditDeposit, Long> {

    List<CreditDeposit> findByClientIdOrderByMovementDateDescIdDesc(Long clientId);

    @Query("SELECT COALESCE(SUM(CASE WHEN d.kind = com.ppms.credit.CreditDepositKind.RECEIVED THEN d.amount "
            + "ELSE -d.amount END), 0) FROM CreditDeposit d WHERE d.clientId = :clientId")
    BigDecimal heldByClientId(@Param("clientId") Long clientId);

    /** Rows of [clientId, deposit held]. */
    @Query("SELECT d.clientId, SUM(CASE WHEN d.kind = com.ppms.credit.CreditDepositKind.RECEIVED THEN d.amount "
            + "ELSE -d.amount END) FROM CreditDeposit d WHERE d.clientId IN :clientIds GROUP BY d.clientId")
    List<Object[]> heldByClientIds(@Param("clientIds") Collection<Long> clientIds);
}
