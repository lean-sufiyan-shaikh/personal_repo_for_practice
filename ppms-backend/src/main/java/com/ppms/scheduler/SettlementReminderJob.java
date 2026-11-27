package com.ppms.scheduler;

import com.ppms.common.time.BusinessClock;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.settlement.PaymentSettlementConfig;
import com.ppms.settlement.PaymentSettlementConfigRepository;
import com.ppms.settlement.PaymentSettlementService;
import com.ppms.settlement.PendingSettlementRange;
import com.ppms.settlement.SettlementPaymentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settlement alerts per pump and payment type, for every enabled settlement config.
 *
 * 1. Runs every 5 minutes alongside NotificationGenerationJob.
 * 2. Once the configured alert time has passed and money of that type is still pending, a daily
 *    SETTLEMENT_REMINDER says how much is pending. Nothing pending, no reminder.
 * 3. While collections older than T+2 are still not in the bank, a daily SETTLEMENT_OVERDUE says how much
 *    and since when, whatever the time of day.
 *
 * Both are keyed {type}:{pumpId}:{today} through NotificationDedupService, so each fires at most once a day and
 * replaces the previous day's one. Owners, admins and managers receive them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementReminderJob {

    private final PaymentSettlementConfigRepository configRepository;
    private final PaymentSettlementService          settlementService;
    private final NotificationDedupService          dedupService;
    private final BusinessClock businessClock;

    @Scheduled(fixedDelay = 5 * 60 * 1000)   // every 5 minutes
    @SchedulerLock(name = "SettlementReminderJob", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    public void fireReminders() {
        List<PaymentSettlementConfig> configs = configRepository.findByEnabledTrue();
        if (configs.isEmpty()) return;

        LocalDate today    = businessClock.today();
        LocalTime nowTime  = businessClock.now().toLocalTime();
        Map<Long, List<PendingSettlementRange>> pendingByPump = new HashMap<>();

        int reminders = 0;
        int overdueAlerts = 0;
        for (PaymentSettlementConfig cfg : configs) {
            Long pumpId = cfg.getPumpId();
            SettlementPaymentType type = cfg.getPaymentType();
            List<PendingSettlementRange> ranges = pendingByPump
                    .computeIfAbsent(pumpId, settlementService::getPendingByAge).stream()
                    .filter(range -> range.paymentType() == type)
                    .toList();

            BigDecimal pending = total(ranges);
            if (pending.signum() > 0 && !nowTime.isBefore(cfg.getAlertTime())) {
                remind(pumpId, type, pending, today);
                reminders++;
            }

            List<PendingSettlementRange> overdue = ranges.stream().filter(PendingSettlementRange::overdue).toList();
            if (!overdue.isEmpty()) {
                alertOverdue(pumpId, type, overdue, today);
                overdueAlerts++;
            }
        }

        if (reminders + overdueAlerts > 0) {
            log.debug("SettlementReminderJob: {} reminder(s) and {} overdue alert(s) processed", reminders, overdueAlerts);
        }
    }

    private void remind(Long pumpId, SettlementPaymentType type, BigDecimal pending, LocalDate today) {
        String typeLabel = toLabel(type);
        dedupService.insertDaily(
                pumpId,
                NotificationType.SETTLEMENT_REMINDER,
                "SETTLEMENT_REMINDER:" + type.name() + ":" + pumpId,
                today,
                typeLabel + " Settlement Reminder",
                "₹" + pending.toPlainString() + " of " + typeLabel + " is pending. Record today's " + typeLabel
                        + " settlement — amount received in bank account. Go to Settlements to log the credit.",
                Map.of("paymentTypeCode", type.name(), "pendingAmount", pending.toPlainString())
        );
    }

    private void alertOverdue(Long pumpId, SettlementPaymentType type, List<PendingSettlementRange> overdue, LocalDate today) {
        String typeLabel = toLabel(type);
        BigDecimal amount = total(overdue);
        LocalDate fromDate = overdue.stream().map(PendingSettlementRange::fromDate).min(Comparator.naturalOrder()).orElseThrow();
        dedupService.insertDaily(
                pumpId,
                NotificationType.SETTLEMENT_OVERDUE,
                "SETTLEMENT_OVERDUE:" + type.name() + ":" + pumpId,
                today,
                typeLabel + " settlement overdue",
                "₹" + amount.toPlainString() + " of " + typeLabel + " collections since " + fromDate
                        + " is past T+2 and not in the bank yet. Record the settlement or follow up with the bank.",
                Map.of("paymentTypeCode", type.name(), "amount", amount.toPlainString(), "fromDate", fromDate.toString())
        );
    }

    private static BigDecimal total(List<PendingSettlementRange> ranges) {
        return ranges.stream().map(PendingSettlementRange::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String toLabel(SettlementPaymentType type) {
        return switch (type) {
            case UPI        -> "UPI";
            case CARD       -> "Card";
            case FLEET_CARD -> "Fleet Card";
        };
    }
}
