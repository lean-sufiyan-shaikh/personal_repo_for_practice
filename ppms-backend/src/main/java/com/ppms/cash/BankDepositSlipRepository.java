package com.ppms.cash;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface BankDepositSlipRepository extends JpaRepository<BankDepositSlip, Long> {

    @Query("SELECT s.bankDepositId FROM BankDepositSlip s WHERE s.bankDepositId IN :depositIds")
    Set<Long> findDepositIdsWithSlip(@Param("depositIds") Collection<Long> depositIds);
}
