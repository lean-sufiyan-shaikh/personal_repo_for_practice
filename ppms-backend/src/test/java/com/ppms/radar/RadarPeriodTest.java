package com.ppms.radar;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RadarPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    @Test
    void radarLooksBackThirtyOrNinetyDaysEndingToday() {
        RadarPeriod thirty = RadarPeriod.endingOn(TODAY, 30);

        assertThat(thirty.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(thirty.to()).isEqualTo(TODAY);
        assertThat(RadarPeriod.endingOn(TODAY, 90).from()).isEqualTo(TODAY.minusDays(89));
    }

    @Test
    void periodsOtherThanThirtyOrNinetyDaysAreRefused() {
        assertThatThrownBy(() -> RadarPeriod.endingOn(TODAY, 45))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.RADAR_PERIOD_INVALID);
                    assertThat(error.getParams()).containsEntry("days", 45);
                });
    }
}
