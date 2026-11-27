package com.ppms.credit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditPortalAttemptRepository extends JpaRepository<CreditPortalAttempt, Long> {

    Optional<CreditPortalAttempt> findByPumpIdAndPhoneHash(Long pumpId, String phoneHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM CreditPortalAttempt a WHERE a.pumpId = :pumpId AND a.phoneHash = :phoneHash")
    Optional<CreditPortalAttempt> findForUpdate(@Param("pumpId") Long pumpId, @Param("phoneHash") String phoneHash);

    /** Creates the row of a phone's first wrong PIN; concurrent first attempts leave one row. */
    @Modifying
    @Query(value = """
            INSERT INTO credit_portal_attempts (pump_id, phone_hash)
            VALUES (:pumpId, :phoneHash)
            ON CONFLICT (pump_id, phone_hash) DO NOTHING
            """, nativeQuery = true)
    void insertIfMissing(@Param("pumpId") Long pumpId, @Param("phoneHash") String phoneHash);

    @Modifying
    @Query("DELETE FROM CreditPortalAttempt a WHERE a.pumpId = :pumpId AND a.phoneHash = :phoneHash")
    void deleteByPumpIdAndPhoneHash(@Param("pumpId") Long pumpId, @Param("phoneHash") String phoneHash);
}
