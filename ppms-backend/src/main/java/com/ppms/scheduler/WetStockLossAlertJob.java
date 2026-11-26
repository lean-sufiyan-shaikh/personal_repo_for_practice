package com.ppms.scheduler;

import com.ppms.inventory.WetStockLossAlertService;
import com.ppms.pump.PumpLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks every pump's tanks against the 7-day wet-stock loss rule once a day, after the previous day's dips are in.
 * Notifications are keyed per tank and day, so a re-run on the same day adds nothing. One pump's failure is logged
 * and the others are still checked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WetStockLossAlertJob {

    private final WetStockLossAlertService alertService;
    private final PumpLocationRepository pumpLocationRepository;

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "WetStockLossAlertJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void checkAllPumps() {
        int pumpsChecked = 0;
        int tanksAboveLimit = 0;
        int failed = 0;
        for (Long pumpId : pumpLocationRepository.findAllIds()) {
            try {
                tanksAboveLimit += alertService.raiseLossAlerts(pumpId);
                pumpsChecked++;
            } catch (RuntimeException e) {
                log.error("Wet-stock loss check failed for pump={}: {}", pumpId, e.getMessage(), e);
                failed++;
            }
        }
        log.info("WetStockLossAlertJob complete: pumpsChecked={} tanksAboveLimit={} failed={}",
                pumpsChecked, tanksAboveLimit, failed);
    }
}
