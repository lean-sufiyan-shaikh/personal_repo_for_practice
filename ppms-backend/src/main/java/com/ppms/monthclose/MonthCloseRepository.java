package com.ppms.monthclose;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MonthCloseRepository extends JpaRepository<MonthClose, Long> {

    Optional<MonthClose> findByPumpIdAndMonthStart(Long pumpId, LocalDate monthStart);

    Page<MonthClose> findByPumpIdOrderByMonthStartDesc(Long pumpId, Pageable pageable);

    @Query("""
            SELECT m.monthStart FROM MonthClose m
            WHERE m.pumpId = :pumpId AND m.monthStart IN :monthStarts AND m.status = :status
            ORDER BY m.monthStart
            """)
    List<LocalDate> findMonthStartsWithStatus(@Param("pumpId") Long pumpId,
                                              @Param("monthStarts") Collection<LocalDate> monthStarts,
                                              @Param("status") MonthCloseStatus status);
}
