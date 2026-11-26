package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ShiftFuelReadingRepository extends JpaRepository<ShiftFuelReading, Long> {

    List<ShiftFuelReading> findByShiftId(Long shiftId);

    void deleteByShiftId(Long shiftId);

    /** Batch fetch for balance sheet generation — avoids N+1 across multiple shifts */
    List<ShiftFuelReading> findByShiftIdIn(java.util.Collection<Long> shiftIds);

    /** Opening readings of shifts not yet closed whose nozzles draw from the tank; their sales have not left its stock. */
    @Query("""
            SELECT r FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND r.tankId = :tankId
              AND s.status IN ('OPEN', 'OPEN_OVERDUE', 'AUTO_CLOSED_OVERDUE')
            ORDER BY r.nozzleId
            """)
    List<ShiftFuelReading> findOpenShiftReadingsByTankId(@Param("tankId") Long tankId);

    /**
     * Litres sold per tank and shift date by closed shifts — the same readings a DAY balance sheet sums.
     * Each row is [tankId, shiftDate, litres].
     */
    @Query("""
            SELECT r.tankId, s.shiftDate, SUM(r.unitsSold) FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND s.pumpId = :pumpId
              AND s.shiftDate BETWEEN :from AND :to
              AND s.status NOT IN ('OPEN', 'OPEN_OVERDUE', 'AUTO_CLOSED_OVERDUE')
              AND r.tankId IS NOT NULL AND r.unitsSold IS NOT NULL
            GROUP BY r.tankId, s.shiftDate
            """)
    List<Object[]> sumClosedLitresSoldByTankAndShiftDate(@Param("pumpId") Long pumpId,
                                                         @Param("from") LocalDate from,
                                                         @Param("to") LocalDate to);
}
