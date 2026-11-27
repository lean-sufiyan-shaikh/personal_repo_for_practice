package com.ppms.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShortageClaimRepository extends JpaRepository<ShortageClaim, Long> {

    interface StatusTotals {
        ShortageClaimStatus getStatus();

        long getClaimCount();

        BigDecimal getClaimAmount();

        BigDecimal getCreditNoteAmount();
    }

    Optional<ShortageClaim> findByTankerDeliveryId(Long tankerDeliveryId);

    List<ShortageClaim> findByTankerDeliveryIdIn(Collection<Long> tankerDeliveryIds);

    Optional<ShortageClaim> findByIdAndPumpId(Long id, Long pumpId);

    List<ShortageClaim> findByPumpIdAndStatus(Long pumpId, ShortageClaimStatus status);

    Page<ShortageClaim> findByPumpIdOrderByCreatedAtDescIdDesc(Long pumpId, Pageable pageable);

    Page<ShortageClaim> findByPumpIdAndStatusOrderByCreatedAtDescIdDesc(Long pumpId, ShortageClaimStatus status,
                                                                        Pageable pageable);

    @Query("""
            SELECT c.status AS status, COUNT(c) AS claimCount,
                   COALESCE(SUM(c.claimAmount), 0) AS claimAmount,
                   COALESCE(SUM(c.creditNoteAmount), 0) AS creditNoteAmount
            FROM ShortageClaim c
            WHERE c.pumpId = :pumpId
            GROUP BY c.status
            """)
    List<StatusTotals> totalsByStatus(@Param("pumpId") Long pumpId);

    List<ShortageClaim> findByPumpIdAndStatusAndCreatedAtBefore(Long pumpId, ShortageClaimStatus status,
                                                               OffsetDateTime raisedBefore);
}
