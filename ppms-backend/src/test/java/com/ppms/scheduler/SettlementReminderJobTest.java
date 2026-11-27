package com.ppms.scheduler;

import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.settlement.PaymentSettlementConfig;
import com.ppms.settlement.PaymentSettlementConfigRepository;
import com.ppms.settlement.PaymentSettlementService;
import com.ppms.settlement.PendingSettlementRange;
import com.ppms.settlement.SettlementPaymentType;
import com.ppms.support.FixedBusinessClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementReminderJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Long PUMP_ID = 3L;

    private final PaymentSettlementConfigRepository configRepository = mock(PaymentSettlementConfigRepository.class);
    private final PaymentSettlementService settlementService = mock(PaymentSettlementService.class);
    private final NotificationDedupService dedupService = mock(NotificationDedupService.class);

    @Test
    void noReminderIsRaisedBeforeTheAlertTime() {
        givenUpiAlertAt("18:00");
        givenPending(range(SettlementPaymentType.UPI, TODAY, "500.00", false));

        jobRunningAt("17:59").fireReminders();

        verify(dedupService, never()).insertDaily(any(), any(), anyString(), any(), anyString(), anyString(), anyMap());
    }

    @Test
    void reminderIsRaisedFromTheAlertTimeOnwardsWithThePendingAmountAsADailyAlertPerPumpAndType() {
        givenUpiAlertAt("18:00");
        givenPending(range(SettlementPaymentType.UPI, TODAY.minusDays(1), "300.00", false),
                range(SettlementPaymentType.UPI, TODAY, "200.00", false),
                range(SettlementPaymentType.CARD, TODAY, "999.00", false));

        jobRunningAt("18:00").fireReminders();

        verify(dedupService).insertDaily(eq(PUMP_ID), eq(NotificationType.SETTLEMENT_REMINDER),
                eq("SETTLEMENT_REMINDER:UPI:" + PUMP_ID), eq(TODAY), eq("UPI Settlement Reminder"), contains("₹500.00"),
                eq(Map.of("paymentTypeCode", "UPI", "pendingAmount", "500.00")));
    }

    @Test
    void noReminderIsRaisedWhenNothingOfThatTypeIsPending() {
        givenUpiAlertAt("18:00");
        givenPending(range(SettlementPaymentType.CARD, TODAY, "999.00", false));

        jobRunningAt("20:00").fireReminders();

        verify(dedupService, never()).insertDaily(any(), any(), anyString(), any(), anyString(), anyString(), anyMap());
    }

    @Test
    void collectionsPastTPlusTwoRaiseADailyOverdueAlertEvenBeforeTheAlertTime() {
        givenUpiAlertAt("18:00");
        givenPending(range(SettlementPaymentType.UPI, TODAY.minusDays(5), "120.00", true),
                range(SettlementPaymentType.UPI, TODAY.minusDays(4), "80.00", true),
                range(SettlementPaymentType.UPI, TODAY, "50.00", false));

        jobRunningAt("09:00").fireReminders();

        verify(dedupService).insertDaily(eq(PUMP_ID), eq(NotificationType.SETTLEMENT_OVERDUE),
                eq("SETTLEMENT_OVERDUE:UPI:" + PUMP_ID), eq(TODAY), eq("UPI settlement overdue"), anyString(),
                eq(Map.of("paymentTypeCode", "UPI", "amount", "200.00", "fromDate", TODAY.minusDays(5).toString())));
        verify(dedupService, never()).insertDaily(any(), eq(NotificationType.SETTLEMENT_REMINDER), anyString(), any(),
                anyString(), anyString(), anyMap());
    }

    @Test
    void noOverdueAlertWhileEveryPendingDayIsWithinTPlusTwo() {
        givenUpiAlertAt("18:00");
        givenPending(range(SettlementPaymentType.UPI, TODAY.minusDays(2), "120.00", false));

        jobRunningAt("19:00").fireReminders();

        verify(dedupService, never()).insertDaily(any(), eq(NotificationType.SETTLEMENT_OVERDUE), anyString(), any(),
                anyString(), anyString(), anyMap());
    }

    @Test
    void alertTimeIsComparedInIndianTimeWhateverZoneTheServerClockUses() {
        givenUpiAlertAt("18:00");
        givenPending(range(SettlementPaymentType.UPI, TODAY, "10.00", false));
        OffsetDateTime sixPmIstSeenFromUtc = LocalDateTime.of(TODAY, LocalTime.parse("12:30"))
                .atZone(ZoneId.of("UTC")).toOffsetDateTime();

        new SettlementReminderJob(configRepository, settlementService, dedupService, new FixedBusinessClock(sixPmIstSeenFromUtc))
                .fireReminders();

        verify(dedupService).insertDaily(eq(PUMP_ID), eq(NotificationType.SETTLEMENT_REMINDER),
                eq("SETTLEMENT_REMINDER:UPI:" + PUMP_ID), eq(TODAY), anyString(), anyString(), anyMap());
    }

    private void givenUpiAlertAt(String alertTime) {
        when(configRepository.findByEnabledTrue()).thenReturn(List.of(PaymentSettlementConfig.builder()
                .pumpId(PUMP_ID)
                .paymentType(SettlementPaymentType.UPI)
                .alertTime(LocalTime.parse(alertTime))
                .enabled(true)
                .build()));
    }

    private void givenPending(PendingSettlementRange... ranges) {
        when(settlementService.getPendingByAge(PUMP_ID)).thenReturn(List.of(ranges));
    }

    private static PendingSettlementRange range(SettlementPaymentType type, LocalDate day, String amount, boolean overdue) {
        BigDecimal value = new BigDecimal(amount);
        return new PendingSettlementRange(type, day, day, value, TODAY.toEpochDay() - day.toEpochDay(), overdue,
                List.of(new PendingSettlementRange.Day(day, value)));
    }

    private SettlementReminderJob jobRunningAt(String time) {
        OffsetDateTime now = LocalDateTime.of(TODAY, LocalTime.parse(time))
                .atZone(ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
        return new SettlementReminderJob(configRepository, settlementService, dedupService, new FixedBusinessClock(now));
    }
}
