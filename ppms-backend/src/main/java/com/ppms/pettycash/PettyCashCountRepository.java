package com.ppms.pettycash;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface PettyCashCountRepository extends JpaRepository<PettyCashCount, Long> {

    Page<PettyCashCount> findByPumpIdOrderByCountForDateDescCountedAtDescIdDesc(Long pumpId, Pageable pageable);

    Optional<PettyCashCount> findFirstByPumpIdOrderByCountForDateDescCountedAtDescIdDesc(Long pumpId);

    boolean existsByPumpIdAndCountForDate(Long pumpId, LocalDate countForDate);
}
