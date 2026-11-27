package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShiftNozzleTurnRepository extends JpaRepository<ShiftNozzleTurn, Long> {

    List<ShiftNozzleTurn> findByShiftIdOrderByStartedAtAscIdAsc(Long shiftId);

    List<ShiftNozzleTurn> findByShiftIdAndEndedAtIsNull(Long shiftId);
}
