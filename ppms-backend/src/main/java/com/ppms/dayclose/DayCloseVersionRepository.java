package com.ppms.dayclose;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DayCloseVersionRepository extends JpaRepository<DayCloseVersion, Long> {

    Optional<DayCloseVersion> findByDayCloseIdAndVersion(Long dayCloseId, int version);

    List<DayCloseVersion> findByDayCloseIdOrderByVersionDesc(Long dayCloseId);

    /** The version each listed day close currently shows. */
    @Query("""
            SELECT v FROM DayCloseVersion v, DayClose d
            WHERE v.dayCloseId = d.id AND v.version = d.currentVersion AND d.id IN :dayCloseIds
            """)
    List<DayCloseVersion> findCurrentVersions(@Param("dayCloseIds") Collection<Long> dayCloseIds);
}
