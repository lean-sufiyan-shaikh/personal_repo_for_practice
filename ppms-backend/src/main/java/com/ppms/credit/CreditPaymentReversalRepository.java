package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Repository
public interface CreditPaymentReversalRepository extends JpaRepository<CreditPaymentReversal, Long> {

    List<CreditPaymentReversal> findByClientId(Long clientId);

    @Query("SELECT r FROM CreditPaymentReversal r WHERE r.clientId IN :clientIds AND r.bounceCharge IS NOT NULL")
    List<CreditPaymentReversal> findWithBounceChargeByClientIdIn(@Param("clientIds") Collection<Long> clientIds);

    @Query("SELECT COALESCE(SUM(r.bounceCharge), 0) FROM CreditPaymentReversal r WHERE r.clientId = :clientId")
    BigDecimal sumBounceChargesByClientId(@Param("clientId") Long clientId);

    /** Rows of [clientId, total bounce charges] for the clients that have any. */
    @Query("SELECT r.clientId, SUM(r.bounceCharge) FROM CreditPaymentReversal r WHERE r.clientId IN :clientIds"
            + " AND r.bounceCharge IS NOT NULL GROUP BY r.clientId")
    List<Object[]> sumBounceChargesByClientIds(@Param("clientIds") Collection<Long> clientIds);
}
