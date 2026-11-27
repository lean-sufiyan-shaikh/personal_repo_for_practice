package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShiftAmendmentRepository extends JpaRepository<ShiftAmendment, Long> {

    List<ShiftAmendment> findByShiftIdOrderByAmendedAtDescIdDesc(Long shiftId);
}
