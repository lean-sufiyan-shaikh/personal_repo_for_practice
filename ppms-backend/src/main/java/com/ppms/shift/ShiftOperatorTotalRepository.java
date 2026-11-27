package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShiftOperatorTotalRepository extends JpaRepository<ShiftOperatorTotal, ShiftOperatorTotalId> {

    List<ShiftOperatorTotal> findByShiftIdOrderByOperatorIdAsc(Long shiftId);
}
