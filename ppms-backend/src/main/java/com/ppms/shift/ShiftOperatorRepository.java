package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShiftOperatorRepository extends JpaRepository<ShiftOperator, Long> {

    List<ShiftOperator> findByShiftIdOrderByJoinedAtAscIdAsc(Long shiftId);

    List<ShiftOperator> findByShiftIdIn(Collection<Long> shiftIds);

    Optional<ShiftOperator> findByOperatorIdAndLeftAtIsNull(Long operatorId);

    Optional<ShiftOperator> findByShiftIdAndOperatorIdAndLeftAtIsNull(Long shiftId, Long operatorId);

    boolean existsByShiftIdAndOperatorId(Long shiftId, Long operatorId);

    /** Times an operator worked a shift opened for someone else and has since left it, dated by the shift. */
    @Query("""
            SELECT so FROM ShiftOperator so, Shift s
            WHERE so.shiftId = s.id AND s.pumpId = :pumpId AND so.operatorId = :operatorId
              AND s.shiftDate BETWEEN :from AND :to AND so.leftAt IS NOT NULL
            """)
    List<ShiftOperator> findFinishedWorkOnOtherShifts(@Param("pumpId") Long pumpId, @Param("operatorId") Long operatorId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    @Modifying
    @Query("UPDATE ShiftOperator so SET so.leftAt = :leftAt WHERE so.shiftId = :shiftId AND so.leftAt IS NULL")
    int releaseAllOfShift(@Param("shiftId") Long shiftId, @Param("leftAt") OffsetDateTime leftAt);
}
