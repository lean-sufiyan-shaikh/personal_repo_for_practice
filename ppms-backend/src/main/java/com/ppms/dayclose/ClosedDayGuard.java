package com.ppms.dayclose;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses a write dated on a closed business day. Every service that creates, edits or voids a record that falls on a
 * business day calls this before saving, so a closed day's figures only change after an owner or admin re-opens it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClosedDayGuard {

    private static final Set<DayCloseStatus> LOCKED_STATUSES = EnumSet.of(DayCloseStatus.CLOSED, DayCloseStatus.APPROVED);

    private final DayCloseRepository dayCloseRepository;
    private final BusinessClock businessClock;

    public void requireOpen(Long pumpId, LocalDate businessDate) {
        requireOpen(pumpId, List.of(businessDate));
    }

    public void requireOpen(Long pumpId, Collection<LocalDate> businessDates) {
        List<LocalDate> dates = businessDates.stream().filter(Objects::nonNull).distinct().toList();
        if (dates.isEmpty()) {
            return;
        }
        List<LocalDate> closed = dayCloseRepository.findDatesWithStatus(pumpId, dates, LOCKED_STATUSES);
        if (closed.isEmpty()) {
            return;
        }
        LocalDate date = closed.get(0);
        log.info("Write refused on closed day: pump={}, date={}", pumpId, date);
        throw new BusinessException(ErrorCodes.DAY_CLOSED, Map.of("date", date.toString()),
                date + " is closed. An owner or admin must re-open the day before it can be changed.");
    }

    /** For records stamped with the moment they are saved, which fall on today's business day. */
    public void requireTodayOpen(Long pumpId) {
        requireOpen(pumpId, businessClock.today());
    }
}
