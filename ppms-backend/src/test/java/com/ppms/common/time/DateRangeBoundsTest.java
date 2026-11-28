package com.ppms.common.time;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeBoundsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void rangeMustRunForwards() {
        assertThatThrownBy(() -> DateRangeBounds.requireRangeOfAtMost(TODAY, TODAY.minusDays(1), 366))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.DATE_RANGE_START_AFTER_END));
    }

    @Test
    void rangeCountingBothEndsMayBeExactlyTheLimitButNotADayLonger() {
        assertThatCode(() -> DateRangeBounds.requireRangeOfAtMost(TODAY.minusDays(365), TODAY, 366)).doesNotThrowAnyException();
        assertThatCode(() -> DateRangeBounds.requireRangeOfAtMost(TODAY, TODAY, 1)).doesNotThrowAnyException();

        assertThatThrownBy(() -> DateRangeBounds.requireRangeOfAtMost(TODAY.minusDays(366), TODAY, 366))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.DATE_RANGE_TOO_LONG);
                    assertThat(error.getParams()).containsEntry("maxDays", 366);
                });
    }

    @Test
    void missingEndsBecomeDatesPostgresCanStore() {
        assertThat(DateRangeBounds.startOrEarliest(null)).isEqualTo(LocalDate.of(1900, 1, 1));
        assertThat(DateRangeBounds.endOrLatest(null)).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(DateRangeBounds.startOrEarliest(TODAY)).isEqualTo(TODAY);
        assertThat(DateRangeBounds.endOrLatest(TODAY)).isEqualTo(TODAY);
    }
}
