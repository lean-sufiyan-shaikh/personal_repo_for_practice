package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ShiftReadingPriceSegmentRepository extends JpaRepository<ShiftReadingPriceSegment, Long> {

    List<ShiftReadingPriceSegment> findByShiftFuelReadingIdInOrderByStartsAtAscIdAsc(Collection<Long> readingIds);
}
