package com.ppms.shift;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ShiftInvestigationNoteRepository extends JpaRepository<ShiftInvestigationNote, Long> {

    List<ShiftInvestigationNote> findByShiftIdInOrderByWrittenAtAscIdAsc(Collection<Long> shiftIds);
}
