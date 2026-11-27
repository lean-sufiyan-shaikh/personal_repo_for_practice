package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShiftReliefRepository extends JpaRepository<ShiftRelief, Long> {

    List<ShiftRelief> findByShiftIdOrderByStartedAtAscIdAsc(Long shiftId);

    List<ShiftRelief> findByShiftIdAndEndedAtIsNull(Long shiftId);

    Optional<ShiftRelief> findByIdAndShiftId(Long id, Long shiftId);

    Optional<ShiftRelief> findByShiftIdAndRelievedOperatorIdAndEndedAtIsNull(Long shiftId, Long relievedOperatorId);
}
