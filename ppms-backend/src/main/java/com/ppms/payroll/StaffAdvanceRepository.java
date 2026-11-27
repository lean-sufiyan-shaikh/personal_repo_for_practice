package com.ppms.payroll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffAdvanceRepository extends JpaRepository<StaffAdvance, Long> {

    Optional<StaffAdvance> findByIdAndPumpId(Long id, Long pumpId);

    List<StaffAdvance> findByPumpIdOrderByAdvanceDateDescIdDesc(Long pumpId);

    List<StaffAdvance> findByPumpIdAndStaffUserIdOrderByAdvanceDateDescIdDesc(Long pumpId, Long staffUserId);

    /** The staff member's active advances, oldest first, the order payroll recovers them in. */
    List<StaffAdvance> findByPumpIdAndStaffUserIdAndStatusOrderByAdvanceDateAscIdAsc(Long pumpId, Long staffUserId,
                                                                                     StaffAdvanceStatus status);
}
