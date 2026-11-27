package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditDisputeRepository extends JpaRepository<CreditDispute, Long> {

    List<CreditDispute> findByClientIdInAndStatus(Collection<Long> clientIds, CreditDisputeStatus status);

    List<CreditDispute> findByClientIdIn(Collection<Long> clientIds);

    boolean existsByDebitTypeAndDebitIdAndStatus(CreditDebitType debitType, Long debitId, CreditDisputeStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM CreditDispute d WHERE d.id = :id AND d.pumpId = :pumpId")
    Optional<CreditDispute> findByIdAndPumpIdForUpdate(@Param("id") Long id, @Param("pumpId") Long pumpId);
}
