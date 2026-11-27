package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface CreditOpeningBalanceRepository extends JpaRepository<CreditOpeningBalance, Long> {

    List<CreditOpeningBalance> findByClientIdIn(Collection<Long> clientIds);

    boolean existsByClientId(Long clientId);

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM CreditOpeningBalance b WHERE b.clientId = :clientId")
    BigDecimal sumAmountByClientId(@Param("clientId") Long clientId);

    /** Rows of [clientId, amount]. */
    @Query("SELECT b.clientId, b.amount FROM CreditOpeningBalance b WHERE b.clientId IN :clientIds")
    List<Object[]> findAmountsByClientIds(@Param("clientIds") Collection<Long> clientIds);
}
