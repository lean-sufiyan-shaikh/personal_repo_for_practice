package com.ppms.cash;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.shift.DiscrepancyType;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SafeCountTest {

    private static final long PUMP_ID = 4L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private CashEventRepository cashEventRepository;
    @Mock private SafeCountRepository safeCountRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private NotificationDedupService dedupService;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private SafeCountDateCheck safeCountDateCheck;
    @InjectMocks private SafeService safeService;

    private final User manager = User.builder().id(2L).fullName("Manager").role(UserRole.MANAGER).build();

    @Test
    void countWithinToleranceIsQuiet() {
        givenSafeHolds("1000.00", "10.00", SafeCountTiming.ON_THE_DAY);

        SafeCountResponse count = safeService.recordCount(PUMP_ID, request("995.00", null), manager);

        assertThat(count.expectedAmount()).isEqualByComparingTo("1000.00");
        assertThat(count.varianceAmount()).isEqualByComparingTo("-5.00");
        assertThat(count.varianceType()).isEqualTo(DiscrepancyType.SHORT);
        assertThat(count.beyondTolerance()).isFalse();
        verify(dedupService, never()).maybeInsert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void countBeyondToleranceNotifiesTheOwner() {
        givenSafeHolds("1000.00", "10.00", SafeCountTiming.ON_THE_DAY);

        SafeCountResponse count = safeService.recordCount(PUMP_ID, request("1020.00", null), manager);

        assertThat(count.varianceType()).isEqualTo(DiscrepancyType.OVER);
        assertThat(count.beyondTolerance()).isTrue();
        verify(dedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.SAFE_COUNT_VARIANCE), anyString(), anyString(),
                contains("OVER by ₹20.00"), anyMap());
    }

    @Test
    void zeroToleranceMakesAnyDifferenceBeyondIt() {
        givenSafeHolds("0.00", "0.00", SafeCountTiming.ON_THE_DAY);

        assertThat(safeService.recordCount(PUMP_ID, request("0.01", null), manager).beyondTolerance()).isTrue();
    }

    @Test
    void countBreakdownMustAddUp() {
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(pump("10.00")));
        when(businessClock.today()).thenReturn(TODAY);
        RecordSafeCountRequest request = new RecordSafeCountRequest(new BigDecimal("100.00"),
                new CashDenominations(0, 0, 0, 0, 0, 0, new BigDecimal("99")), null, null, null);

        assertThatThrownBy(() -> safeService.recordCount(PUMP_ID, request, manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("DENOMINATIONS_TOTAL_MISMATCH"));
        verify(safeCountRepository, never()).save(any());
    }

    @Test
    void backDatedCountKeepsItsTrimmedReasonAndIsAuditedAsBackDated() {
        givenSafeHolds("1000.00", "10.00", SafeCountTiming.BACK_DATED);

        SafeCountResponse count = safeService.recordCount(PUMP_ID, new RecordSafeCountRequest(new BigDecimal("1000.00"), null,
                null, TODAY, "  Manager was on leave  "), manager);

        assertThat(count.late()).isTrue();
        assertThat(count.backDateReason()).isEqualTo("Manager was on leave");
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.SAFE_COUNT_BACK_DATED), eq("SafeCount"), any(),
                contains("Manager was on leave"), eq(manager));
        verify(closedDayGuard).requireOpen(PUMP_ID, TODAY);
    }

    private void givenSafeHolds(String balance, String tolerance, SafeCountTiming timing) {
        PumpLocation pump = pump(tolerance);
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(pump));
        when(businessClock.today()).thenReturn(TODAY);
        when(safeCountDateCheck.timingOf(eq(pump), eq(TODAY), any(), eq(manager))).thenReturn(timing);
        when(cashEventRepository.computeBalance(PUMP_ID)).thenReturn(new BigDecimal(balance));
        when(safeCountRepository.save(any(SafeCount.class))).thenAnswer(invocation -> {
            SafeCount count = invocation.getArgument(0);
            count.setId(70L);
            return count;
        });
    }

    private static PumpLocation pump(String tolerance) {
        return PumpLocation.builder().id(PUMP_ID).safeCountTolerance(new BigDecimal(tolerance)).build();
    }

    private static RecordSafeCountRequest request(String counted, CashDenominations denominations) {
        return new RecordSafeCountRequest(new BigDecimal(counted), denominations, null, null, null);
    }
}
