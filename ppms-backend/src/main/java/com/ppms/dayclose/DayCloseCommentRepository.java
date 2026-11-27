package com.ppms.dayclose;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DayCloseCommentRepository extends JpaRepository<DayCloseComment, Long> {

    List<DayCloseComment> findByDayCloseIdOrderByCreatedAtAscIdAsc(Long dayCloseId);

    @Query("""
            SELECT c.dayCloseId, COUNT(c) FROM DayCloseComment c
            WHERE c.dayCloseId IN :dayCloseIds
            GROUP BY c.dayCloseId
            """)
    List<Object[]> countByDayCloseIds(@Param("dayCloseIds") Collection<Long> dayCloseIds);
}
