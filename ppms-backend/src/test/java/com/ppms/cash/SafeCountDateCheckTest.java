package com.ppms.cash;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocation;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SafeCountDateCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final OffsetDateTime NINE_AM = OffsetDateTime.of(TODAY, LocalTime.of(9, 0), ZoneOffset.ofHoursMinutes(5, 30));

    @Mock private BusinessClock businessClock;
    @InjectMocks private SafeCountDateCheck dateCheck;

    private final User manager = User.builder().id(2L).role(UserRole.MANAGER).build();
    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();

    @Test
    void countForADayNotYetBegunIsRefused() {
        givenNow();

        assertRefused(pumpWithCutoff("10:00"), TODAY.plusDays(1), null, owner, "SAFE_COUNT_DATE_IN_FUTURE");
    }

    @Test
    void countForTodayIsOnTheDay() {
        givenNow();

        assertThat(dateCheck.timingOf(pumpWithCutoff("10:00"), TODAY, null, manager)).isEqualTo(SafeCountTiming.ON_THE_DAY);
    }

    @Test
    void managerCountsForYesterdayBeforeTheCutoff() {
        givenNow();

        assertThat(dateCheck.timingOf(pumpWithCutoff("10:00"), YESTERDAY, null, manager)).isEqualTo(SafeCountTiming.NEXT_MORNING);
    }

    @Test
    void managerCannotCountForYesterdayAfterTheCutoff() {
        givenNow();

        assertThatThrownBy(() -> dateCheck.timingOf(pumpWithCutoff("09:00"), YESTERDAY, "Forgot to count", manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("SAFE_COUNT_BACK_DATE_NOT_ALLOWED");
                    assertThat(refused.getParams()).containsEntry("date", YESTERDAY.toString())
                            .containsEntry("cutoffTime", "09:00");
                });
    }

    @Test
    void ownerBackDatesACountOnlyWithAReason() {
        givenNow();
        PumpLocation pump = pumpWithCutoff("00:00");

        assertRefused(pump, YESTERDAY, null, owner, "SAFE_COUNT_BACK_DATE_REASON_REQUIRED");
        assertRefused(pump, YESTERDAY, "   ", owner, "SAFE_COUNT_BACK_DATE_REASON_REQUIRED");
        assertThat(dateCheck.timingOf(pump, YESTERDAY, "Manager was on leave", User.builder().role(UserRole.ADMIN).build()))
                .isEqualTo(SafeCountTiming.BACK_DATED);
    }

    private void givenNow() {
        when(businessClock.today()).thenReturn(TODAY);
        lenient().when(businessClock.now()).thenReturn(NINE_AM);
    }

    private void assertRefused(PumpLocation pump, LocalDate date, String reason, User user, String code) {
        assertThatThrownBy(() -> dateCheck.timingOf(pump, date, reason, user))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
    }

    private static PumpLocation pumpWithCutoff(String cutoff) {
        return PumpLocation.builder().id(4L).safeCountCutoffTime(LocalTime.parse(cutoff)).build();
    }
}
