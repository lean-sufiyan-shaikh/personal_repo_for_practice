package com.ppms.cash;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface SafeCountRepository extends JpaRepository<SafeCount, Long> {

    Page<SafeCount> findByPumpIdOrderByCountedAtDescIdDesc(Long pumpId, Pageable pageable);

    Optional<SafeCount> findFirstByPumpIdOrderByCountedAtDescIdDesc(Long pumpId);

    Optional<SafeCount> findFirstByPumpIdAndCountedAtGreaterThanEqualAndCountedAtLessThanOrderByCountedAtDescIdDesc(
            Long pumpId, OffsetDateTime from, OffsetDateTime to);
}
