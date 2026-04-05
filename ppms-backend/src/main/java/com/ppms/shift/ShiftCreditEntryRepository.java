package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftCreditEntryRepository extends JpaRepository<ShiftCreditEntry, Long> {

    List<ShiftCreditEntry> findByShiftId(Long shiftId);

    /** Batch fetch for all credit entries across multiple shifts — avoids N+1 in balance sheet generation. */
    List<ShiftCreditEntry> findByShiftIdIn(java.util.Collection<Long> shiftIds);

    // Returns all credit entries for a specific client — used for the ledger transaction list
    List<ShiftCreditEntry> findByClientIdOrderByCreatedAtDesc(Long clientId);

    // Total credit sales for a client — used to compute outstanding balance
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM ShiftCreditEntry e WHERE e.clientId = :clientId")
    BigDecimal sumAmountByClientId(@Param("clientId") Long clientId);

    /** Batch sum of credit sales per client — used for efficient outstanding balance calculation across all clients. */
    @Query("SELECT e.clientId, COALESCE(SUM(e.amount), 0) FROM ShiftCreditEntry e WHERE e.clientId IN :clientIds GROUP BY e.clientId")
    List<Object[]> sumAmountsByClientIds(@Param("clientIds") java.util.Collection<Long> clientIds);

    /**
     * Date of the oldest credit entry for a client.
     * Used by the interest calculator to determine when the grace period starts.
     * Returns empty if the client has no credit entries yet.
     */
    @Query("SELECT MIN(e.createdAt) FROM ShiftCreditEntry e WHERE e.clientId = :clientId")
    Optional<OffsetDateTime> findOldestEntryDateByClientId(@Param("clientId") Long clientId);
}
