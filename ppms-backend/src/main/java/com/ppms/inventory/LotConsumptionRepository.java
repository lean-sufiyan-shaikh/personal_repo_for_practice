package com.ppms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface LotConsumptionRepository extends JpaRepository<LotConsumption, Long> {

    List<LotConsumption> findByShiftId(Long shiftId);

    /** Batch fetch for all consumptions across multiple shifts — avoids N+1 in P&L report. */
    List<LotConsumption> findByShiftIdIn(java.util.Collection<Long> shiftIds);

    /** All consumptions from a specific lot — used by inventory lots report. */
    List<LotConsumption> findByLotId(Long lotId);

    /** Batch fetch for all consumptions across multiple lots — avoids N+1 in inventory lots report. */
    List<LotConsumption> findByLotIdIn(java.util.Collection<Long> lotIds);

    /**
     * Litres consumed per fuel type at or before {@code asOf}. A shift-close consumption is dated at
     * the shift's end and a dip correction at its check time, so backfilled history lands on the right day.
     */
    @Query("""
            SELECT l.fuelType, SUM(c.quantityConsumed)
            FROM LotConsumption c
            JOIN InventoryLot l ON l.id = c.lotId
            LEFT JOIN Shift s ON s.id = c.shiftId
            LEFT JOIN DipCheck d ON d.id = c.dipCorrectionId
            WHERE l.pumpId = :pumpId
              AND COALESCE(s.actualEndTime, d.checkedAt, c.consumedAt) <= :asOf
            GROUP BY l.fuelType
            """)
    List<Object[]> sumConsumedByFuelTypeUpTo(@Param("pumpId") Long pumpId, @Param("asOf") OffsetDateTime asOf);
}
