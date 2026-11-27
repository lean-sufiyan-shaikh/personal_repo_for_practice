package com.ppms.shift;

import com.ppms.fuel.FuelType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
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

    /** Readings of the pump's shifts not yet closed that sell the fuel, by shift and nozzle. */
    @Query("""
            SELECT r FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND s.pumpId = :pumpId AND r.fuelType = :fuelType
              AND s.status IN ('OPEN', 'OPEN_OVERDUE', 'AUTO_CLOSED_OVERDUE')
            ORDER BY r.shiftId, r.nozzleId
            """)
    List<ShiftFuelReading> findOpenShiftReadingsByPumpIdAndFuelType(@Param("pumpId") Long pumpId,
                                                                    @Param("fuelType") FuelType fuelType);

    /**
     * Tank-linked readings of the pump's shifts that were dispensing at some moment of the window: opened before it ends
     * and still open, or closed at or after it starts. Each row is [reading, status, opened at, closed at, end time].
     */
    @Query("""
            SELECT r, s.status, s.actualStartTime, s.closedAt, s.actualEndTime FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND s.pumpId = :pumpId AND r.tankId IS NOT NULL
              AND s.actualStartTime < :to
              AND (s.status IN ('OPEN', 'OPEN_OVERDUE', 'AUTO_CLOSED_OVERDUE')
                   OR COALESCE(s.closedAt, s.actualEndTime) >= :from)
            """)
    List<Object[]> findTankReadingsOfShiftsDispensingBetween(@Param("pumpId") Long pumpId,
                                                             @Param("from") OffsetDateTime from,
                                                             @Param("to") OffsetDateTime to);

    /** Shifts of the pump that sold litres no delivery covers yet; one row: [shift count, litres]. */
    @Query("""
            SELECT COUNT(DISTINCT r.shiftId), COALESCE(SUM(r.stockShortfallLitres), 0) FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND s.pumpId = :pumpId AND r.stockShortfallLitres > 0
            """)
    List<Object[]> summariseUncoveredShortfallsByPumpId(@Param("pumpId") Long pumpId);

    /** Readings of the tank with litres not yet covered by a delivery, oldest shift first. */
    @Query("""
            SELECT r FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND r.tankId = :tankId AND r.stockShortfallLitres > 0
            ORDER BY s.actualStartTime, r.id
            """)
    List<ShiftFuelReading> findUncoveredShortfallsByTankId(@Param("tankId") Long tankId);
}
