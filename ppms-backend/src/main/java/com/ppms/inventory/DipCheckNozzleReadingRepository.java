package com.ppms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface DipCheckNozzleReadingRepository extends JpaRepository<DipCheckNozzleReading, Long> {

    /** Meter snapshots taken with the pump's dips in the window. Each row is [shiftId, nozzleId, checkedAt, unitsSold]. */
    @Query("""
            SELECT n.shiftId, n.nozzleId, d.checkedAt, n.unitsSold FROM DipCheckNozzleReading n, DipCheck d
            WHERE n.dipCheckId = d.id AND d.pumpId = :pumpId
              AND d.checkedAt >= :from AND d.checkedAt < :to
            """)
    List<Object[]> findSnapshotsTakenWithDipsBetween(@Param("pumpId") Long pumpId,
                                                     @Param("from") OffsetDateTime from,
                                                     @Param("to") OffsetDateTime to);
}
