package com.ppms.cash;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CashDropRepository extends JpaRepository<CashDrop, Long> {

    interface DropTotal {
        long getDropCount();

        BigDecimal getAmount();
    }

    List<CashDrop> findByShiftIdOrderByCreatedAtDescIdDesc(Long shiftId);

    List<CashDrop> findByPumpIdAndStatusOrderByCreatedAtAscIdAsc(Long pumpId, CashDropStatus status);

    /** Reads only the shift id, so the drop itself is first loaded after the shift lock is held. */
    @Query("SELECT d.shiftId FROM CashDrop d WHERE d.id = :id AND d.pumpId = :pumpId")
    Optional<Long> findShiftIdOfDrop(@Param("id") Long id, @Param("pumpId") Long pumpId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM CashDrop d WHERE d.id = :id AND d.pumpId = :pumpId")
    Optional<CashDrop> findByIdAndPumpIdForUpdate(@Param("id") Long id, @Param("pumpId") Long pumpId);

    @Query("""
            SELECT COUNT(d) AS dropCount, COALESCE(SUM(d.amount), 0) AS amount
            FROM CashDrop d
            WHERE d.shiftId = :shiftId AND d.status = :status
            """)
    DropTotal totalForShift(@Param("shiftId") Long shiftId, @Param("status") CashDropStatus status);

    @Query("""
            SELECT COUNT(d) AS dropCount, COALESCE(SUM(d.amount), 0) AS amount
            FROM CashDrop d
            WHERE d.pumpId = :pumpId AND d.status = :status
            """)
    DropTotal totalForPump(@Param("pumpId") Long pumpId, @Param("status") CashDropStatus status);
}
