package com.ppms.monthclose;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonthCloseVersionRepository extends JpaRepository<MonthCloseVersion, Long> {

    Optional<MonthCloseVersion> findByMonthCloseIdAndVersion(Long monthCloseId, int version);

    List<MonthCloseVersion> findByMonthCloseIdOrderByVersionDesc(Long monthCloseId);
}
