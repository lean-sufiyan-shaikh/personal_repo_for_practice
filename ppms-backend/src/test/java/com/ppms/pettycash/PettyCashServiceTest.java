package com.ppms.pettycash;

import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PettyCashServiceTest {

    private static final long PUMP_ID = 4L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private PettyCashBoxRepository boxRepository;
    @Mock private PettyCashCountRepository countRepository;
    @Mock private PettyCashLedger ledger;
    @Mock private CashDrawerService cashDrawerService;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private NotificationDedupService dedupService;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @InjectMocks private PettyCashService pettyCashService;

    private final User manager = User.builder().id(2L).fullName("Manager").role(UserRole.MANAGER).build();

    @Test
    void countWithinToleranceIsQuiet() {
        givenBoxHolds("500.00");

        PettyCashResponses.Count count = pettyCashService.recordCount(PUMP_ID, new PettyCashRequests.Count(new BigDecimal("495"), null, null), manager);

        assertThat(count.varianceAmount()).isEqualByComparingTo("-5.00");
        assertThat(count.beyondTolerance()).isFalse();
        verifyNoInteractions(dedupService);
    }

    @Test
    void countBeyondToleranceNotifiesTheOwnerThatTheBoxIsShort() {
        givenBoxHolds("500.00");

        PettyCashResponses.Count count = pettyCashService.recordCount(PUMP_ID, new PettyCashRequests.Count(new BigDecimal("420"), null, null), manager);

        assertThat(count.expectedAmount()).isEqualByComparingTo("500.00");
        assertThat(count.beyondTolerance()).isTrue();
        verify(dedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.PETTY_CASH_COUNT_VARIANCE), anyString(), anyString(),
                anyString(), argThat((Map<String, Object> params) -> "SHORT".equals(params.get("varianceTypeCode"))));
    }

    @Test
    void topUpDatedInTheFutureIsRefused() {
        when(businessClock.today()).thenReturn(TODAY);

        assertThatThrownBy(() -> pettyCashService.topUp(PUMP_ID, new PettyCashRequests.TopUp(new BigDecimal("10.00"),
                TODAY.plusDays(1), null), manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("PETTY_CASH_DATE_IN_FUTURE"));
        verifyNoInteractions(cashDrawerService, ledger);
    }

    @Test
    void topUpTakesTheCashOutOfTheSafeAndPutsItInTheBox() {
        when(businessClock.today()).thenReturn(TODAY);
        when(cashDrawerService.recordAutomaticCashOut(PUMP_ID, new BigDecimal("800.00"), TODAY, "Petty cash top-up: Weekly refill", manager))
                .thenReturn(CashEvent.builder().id(70L).build());
        when(ledger.recordTopUp(PUMP_ID, new BigDecimal("800.00"), TODAY, "Petty cash top-up: Weekly refill", 70L, manager))
                .thenReturn(PettyCashEntry.builder().id(5L).entryType(PettyCashEntryType.TOP_UP).amount(new BigDecimal("800.00")).build());
        when(ledger.balanceOf(PUMP_ID)).thenReturn(new BigDecimal("800.00"));

        PettyCashResponses.Entry entry = pettyCashService.topUp(PUMP_ID,
                new PettyCashRequests.TopUp(new BigDecimal("800"), null, " Weekly refill "), manager);

        assertThat(entry.balanceAfter()).isEqualByComparingTo("800.00");
    }

    @Test
    void topUpThatTheSafeCannotCoverPutsNothingInTheBox() {
        when(businessClock.today()).thenReturn(TODAY);
        when(cashDrawerService.recordAutomaticCashOut(eq(PUMP_ID), any(), eq(TODAY), anyString(), eq(manager)))
                .thenThrow(new BusinessException("CASH_OUT_EXCEEDS_BALANCE", Map.of(), "Not enough in the safe"));

        assertThatThrownBy(() -> pettyCashService.topUp(PUMP_ID, new PettyCashRequests.TopUp(new BigDecimal("1500"), null, null), manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("CASH_OUT_EXCEEDS_BALANCE"));
        verify(ledger, never()).recordTopUp(any(), any(), any(), any(), any(), any());
    }

    private void givenBoxHolds(String balance) {
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
        when(businessClock.today()).thenReturn(TODAY);
        when(boxRepository.findById(PUMP_ID)).thenReturn(Optional.of(PettyCashBox.builder().pumpId(PUMP_ID)
                .floatAmount(new BigDecimal("500.00")).countTolerance(new BigDecimal("10.00")).build()));
        when(ledger.balanceOf(PUMP_ID)).thenReturn(new BigDecimal(balance));
        when(countRepository.save(any(PettyCashCount.class))).thenAnswer(invocation -> {
            PettyCashCount count = invocation.getArgument(0);
            count.setId(9L);
            return count;
        });
    }
}
