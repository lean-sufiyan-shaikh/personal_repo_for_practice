package com.ppms.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface DipCheckRepository extends JpaRepository<DipCheck, Long> {

    List<DipCheck> findByTankIdOrderByCheckedAtDesc(Long tankId);

    List<DipCheck> findByPumpIdOrderByCheckedAtDesc(Long pumpId);

    Page<DipCheck> findByPumpIdOrderByCheckedAtDesc(Long pumpId, Pageable pageable);

    Page<DipCheck> findByPumpIdAndTankIdInOrderByCheckedAtDesc(Long pumpId, Collection<Long> tankIds, Pageable pageable);

    /** DIP variances checked within [from, to) — used by balance sheet generation. */
    List<DipCheck> findByPumpIdAndCheckedAtGreaterThanEqualAndCheckedAtLessThan(Long pumpId, OffsetDateTime from, OffsetDateTime to);

    @Query("SELECT DISTINCT d.tankId FROM DipCheck d WHERE d.pumpId = :pumpId AND d.checkedAt >= :from AND d.checkedAt < :to")
    List<Long> findTankIdsDippedBetween(@Param("pumpId") Long pumpId,
                                        @Param("from") OffsetDateTime from,
                                        @Param("to") OffsetDateTime to);
}
