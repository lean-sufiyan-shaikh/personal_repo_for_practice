package com.ppms.payroll;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface StaffAdvanceRecoveryRepository extends JpaRepository<StaffAdvanceRecovery, Long> {

    List<StaffAdvanceRecovery> findByAdvanceIdIn(Collection<Long> advanceIds);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM StaffAdvanceRecovery r WHERE r.advanceId = :advanceId")
    BigDecimal sumRecoveredOf(@Param("advanceId") Long advanceId);
}
