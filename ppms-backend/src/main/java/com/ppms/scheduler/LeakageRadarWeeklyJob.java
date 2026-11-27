package com.ppms.scheduler;

import com.ppms.pump.PumpLocationRepository;
import com.ppms.radar.LeakageRadarWeeklySummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends each pump's weekly leakage radar summary on Monday morning. The notification is keyed by the week's Monday,
 * so a re-run in the same week adds nothing. One pump's failure is logged and the others are still checked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeakageRadarWeeklyJob {

    private final LeakageRadarWeeklySummaryService summaryService;
    private final PumpLocationRepository pumpLocationRepository;

    @Scheduled(cron = "0 30 8 * * MON", zone = "Asia/Kolkata")
    @SchedulerLock(name = "LeakageRadarWeeklyJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void sendWeeklySummaries() {
        int pumpsChecked = 0;
        int pumpsNotified = 0;
        int failed = 0;
        for (Long pumpId : pumpLocationRepository.findAllIds()) {
            try {
                if (summaryService.sendWeeklySummary(pumpId) > 0) {
                    pumpsNotified++;
                }
                pumpsChecked++;
            } catch (RuntimeException e) {
                log.error("Leakage radar weekly summary failed for pump={}: {}", pumpId, e.getMessage(), e);
                failed++;
            }
        }
        log.info("LeakageRadarWeeklyJob complete: pumpsChecked={} pumpsNotified={} failed={}",
                pumpsChecked, pumpsNotified, failed);
    }
}
