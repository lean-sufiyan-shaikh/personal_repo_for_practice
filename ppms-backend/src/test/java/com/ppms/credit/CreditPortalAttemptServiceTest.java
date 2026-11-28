package com.ppms.credit;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.TooManyRequestsException;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.support.FixedBusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditPortalAttemptServiceTest {

    private static final long PUMP_ID = 7L;
    private static final String PHONE = "9876543210";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-03-06T10:00:00+05:30");

    private final CreditPortalAttemptRepository attemptRepository = mock(CreditPortalAttemptRepository.class);
    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final NotificationDedupService notificationDedupService = mock(NotificationDedupService.class);

    private final CreditPortalAttemptService attemptService = new CreditPortalAttemptService(attemptRepository,
            clientRepository, notificationDedupService, mock(AuditService.class), new FixedBusinessClock(NOW));

    private final CreditPortalAttempt attempt = CreditPortalAttempt.builder().pumpId(PUMP_ID)
            .phoneHash(CreditPortalAttemptService.phoneHash(PUMP_ID, PHONE)).build();

    @BeforeEach
    void givenAttemptRow() {
        when(attemptRepository.findForUpdate(PUMP_ID, attempt.getPhoneHash())).thenReturn(Optional.of(attempt));
        when(attemptRepository.findByPumpIdAndPhoneHash(PUMP_ID, attempt.getPhoneHash())).thenReturn(Optional.of(attempt));
        when(clientRepository.findById(1L)).thenReturn(Optional.of(
                CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Fleet Co").build()));
    }

    @Test
    void fourWrongPinsDoNotLockThePhone() {
        failTimes(4, 1L);

        assertThat(attempt.getLockedUntil()).isNull();
        assertThatNoException().isThrownBy(() -> attemptService.requireNotLocked(PUMP_ID, PHONE));
        verify(notificationDedupService, never()).maybeInsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fifthWrongPinLocksThePhoneForFifteenMinutesAndTellsTheOwner() {
        failTimes(5, 1L);

        assertThat(attempt.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
        assertThatThrownBy(() -> attemptService.requireNotLocked(PUMP_ID, PHONE))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        refusal -> assertThat(refusal.getParams()).containsEntry("minutes", 15L));
        verify(notificationDedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.CREDIT_PORTAL_LOCKED),
                startsWith("CREDIT_PORTAL_LOCKED:client:1:"), anyString(), anyString(), anyMap());
    }

    @Test
    void nextLockoutLastsTwiceAsLong() {
        failTimes(5, 1L);
        failTimes(5, 1L);

        assertThat(attempt.getLockCount()).isEqualTo(2);
        assertThat(attempt.getLockedUntil()).isEqualTo(NOW.plusMinutes(30));
    }

    @Test
    void phoneOfNoClientIsLockedTheSameWay() {
        failTimes(5, null);

        assertThat(attempt.getLockedUntil()).isEqualTo(NOW.plusMinutes(15));
        verify(notificationDedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.CREDIT_PORTAL_LOCKED),
                startsWith("CREDIT_PORTAL_LOCKED:phone:"), anyString(), anyString(), anyMap());
    }

    @Test
    void phoneIsKeptOnlyAsAHash() {
        assertThat(CreditPortalAttemptService.phoneHash(PUMP_ID, PHONE)).doesNotContain(PHONE).hasSize(64);
    }

    private void failTimes(int times, Long clientId) {
        for (int i = 0; i < times; i++) {
            attemptService.recordFailure(PUMP_ID, PHONE, clientId);
        }
    }
}
