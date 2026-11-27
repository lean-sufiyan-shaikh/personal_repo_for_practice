package com.ppms.dayclose;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DayCloseRepository extends JpaRepository<DayClose, Long> {

    Optional<DayClose> findByPumpIdAndBusinessDate(Long pumpId, LocalDate businessDate);

    @Query("""
            SELECT d.businessDate FROM DayClose d
            WHERE d.pumpId = :pumpId AND d.businessDate IN :dates AND d.status IN :statuses
            ORDER BY d.businessDate
            """)
    List<LocalDate> findDatesWithStatus(@Param("pumpId") Long pumpId, @Param("dates") Collection<LocalDate> dates,
                                        @Param("statuses") Collection<DayCloseStatus> statuses);

    Page<DayClose> findByPumpIdOrderByBusinessDateDesc(Long pumpId, Pageable pageable);

    Page<DayClose> findByPumpIdAndStatusOrderByBusinessDateDesc(Long pumpId, DayCloseStatus status, Pageable pageable);

    long countByPumpIdAndStatus(Long pumpId, DayCloseStatus status);
}
