package com.ppms.cash;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocation;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * Decides whether a safe count may be recorded for a business day: today always; yesterday until the pump's morning
 * cut-off; any earlier day only by an owner or admin who gives a reason.
 */
@Component
@RequiredArgsConstructor
class SafeCountDateCheck {

    private final BusinessClock businessClock;

    SafeCountTiming timingOf(PumpLocation pump, LocalDate countForDate, String backDateReason, User currentUser) {
        LocalDate today = businessClock.today();
        if (countForDate.isAfter(today)) {
            throw new BusinessException(ErrorCodes.SAFE_COUNT_DATE_IN_FUTURE, Map.of("date", countForDate.toString()),
                    "A safe count cannot be recorded for " + countForDate + ", which has not begun.");
        }
        if (countForDate.equals(today)) {
            return SafeCountTiming.ON_THE_DAY;
        }
        if (countForDate.equals(today.minusDays(1)) && isYesterdayOpen(pump)) {
            return SafeCountTiming.NEXT_MORNING;
        }
        if (currentUser.getRole() != UserRole.OWNER && currentUser.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCodes.SAFE_COUNT_BACK_DATE_NOT_ALLOWED,
                    Map.of("date", countForDate.toString(), "cutoffTime", pump.getSafeCountCutoffTime().toString()),
                    "Only an owner or admin can record a safe count for " + countForDate + " after "
                            + pump.getSafeCountCutoffTime() + " the next morning.");
        }
        if (backDateReason == null || backDateReason.isBlank()) {
            throw new BusinessException(ErrorCodes.SAFE_COUNT_BACK_DATE_REASON_REQUIRED, Map.of("date", countForDate.toString()),
                    "Give a reason for recording a safe count for " + countForDate + ".");
        }
        return SafeCountTiming.BACK_DATED;
    }

    boolean isYesterdayOpen(PumpLocation pump) {
        return businessClock.now().toLocalTime().isBefore(pump.getSafeCountCutoffTime());
    }
}
