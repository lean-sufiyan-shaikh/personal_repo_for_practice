package com.ppms.adjustment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NozzleReadingAdjustmentRepository extends JpaRepository<NozzleReadingAdjustment, Long> {

    List<NozzleReadingAdjustment> findByNozzleIdOrderByCreatedAtDesc(Long nozzleId);

    /** Applied amendments recorded within [from, to); requests still waiting or rejected never moved a meter. */
    @Query("""
            SELECT a FROM NozzleReadingAdjustment a
            WHERE a.pumpId = :pumpId AND a.createdAt >= :from AND a.createdAt < :to
              AND a.status = com.ppms.adjustment.MeterAdjustmentStatus.APPLIED
            ORDER BY a.createdAt ASC
            """)
    List<NozzleReadingAdjustment> findByPumpIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            @Param("pumpId") Long pumpId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    List<NozzleReadingAdjustment> findByPumpIdAndStatusOrderByCreatedAtAsc(Long pumpId, MeterAdjustmentStatus status);

    boolean existsByNozzleIdAndStatus(Long nozzleId, MeterAdjustmentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM NozzleReadingAdjustment a WHERE a.id = :id")
    Optional<NozzleReadingAdjustment> findByIdForUpdate(@Param("id") Long id);
}
