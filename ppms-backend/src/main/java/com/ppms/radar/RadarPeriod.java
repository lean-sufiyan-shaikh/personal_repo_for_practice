package com.ppms.radar;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The days the radar looks back over, ending today: 30 or 90. */
record RadarPeriod(int days, LocalDate from, LocalDate to) {

    static final List<Integer> ALLOWED_DAYS = List.of(30, 90);

    static RadarPeriod endingOn(LocalDate today, int days) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new BusinessException(ErrorCodes.RADAR_PERIOD_INVALID, Map.of("days", days),
                    "The radar looks back over 30 or 90 days, not " + days);
        }
        return new RadarPeriod(days, today.minusDays(days - 1L), today);
    }

    /** The first day of the period's second half; shortages from here on are the recent ones. */
    LocalDate secondHalfStart() {
        return from.plusDays(days / 2);
    }
}
