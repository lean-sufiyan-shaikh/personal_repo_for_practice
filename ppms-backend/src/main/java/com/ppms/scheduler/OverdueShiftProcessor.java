package com.ppms.scheduler;

import com.ppms.pump.PumpShiftDefinition;
import com.ppms.pump.PumpShiftDefinitionRepository;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Applies the overdue transition to one shift in its own transaction, holding the shift row lock
 * so a concurrent manual close is never overwritten.
 */
@Component
@RequiredArgsConstructor
public class OverdueShiftProcessor {

    public enum Outcome { MARKED_OVERDUE, AUTO_CLOSED, UNCHANGED }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ShiftRepository shiftRepository;
    private final PumpShiftDefinitionRepository shiftDefinitionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome process(Long shiftId, OffsetDateTime now) {
        Optional<Shift> locked = shiftRepository.findByIdForUpdate(shiftId);
        if (locked.isEmpty()) {
            return Outcome.UNCHANGED;
        }
        Shift shift = locked.get();

        if (shift.getStatus() == ShiftStatus.OPEN_OVERDUE) {
            shift.setStatus(ShiftStatus.AUTO_CLOSED_OVERDUE);
            shift.setActualEndTime(now);
            shiftRepository.save(shift);
            return Outcome.AUTO_CLOSED;
        }

        if (shift.getStatus() != ShiftStatus.OPEN || shift.getShiftDefinitionId() == null) {
            return Outcome.UNCHANGED;
        }

        boolean pastEnd = shiftDefinitionRepository.findById(shift.getShiftDefinitionId())
                .map(def -> isPastEndTime(def, now.atZoneSameInstant(IST).toLocalTime(),
                        shift.getShiftDate(), now.atZoneSameInstant(IST).toLocalDate()))
                .orElse(false);
        if (!pastEnd) {
            return Outcome.UNCHANGED;
        }

        shift.setStatus(ShiftStatus.OPEN_OVERDUE);
        shift.setIsOverdueFlag(true);
        shiftRepository.save(shift);
        return Outcome.MARKED_OVERDUE;
    }

    /**
     * Cross-midnight definitions end at endTime on the day after shiftDate; same-day definitions
     * end at endTime on shiftDate.
     */
    private boolean isPastEndTime(PumpShiftDefinition def, LocalTime now,
                                  LocalDate shiftDate, LocalDate today) {
        LocalDate expectedEndDate = def.isCrossesMidnight() ? shiftDate.plusDays(1) : shiftDate;
        if (today.isAfter(expectedEndDate)) {
            return true;
        }
        return today.equals(expectedEndDate) && !now.isBefore(def.getEndTime());
    }
}
