package com.ppms.scheduler;

import com.ppms.collection.CreditFollowUpService;
import com.ppms.collection.CreditPromiseService;
import com.ppms.pump.PumpLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Every morning, for each pump: resolves promises to pay (kept, or missed with a notification), then tells the
 * managers who is due a reminder and whose credit went on hold. Holds themselves are worked out whenever credit is
 * given, so the job only notifies. One failing pump is logged and the others still run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditFollowUpJob {

    private final CreditPromiseService promiseService;
    private final CreditFollowUpService followUpService;
    private final PumpLocationRepository pumpLocationRepository;

    @Scheduled(cron = "0 30 6 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "CreditFollowUpJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDailyFollowUp() {
        List<Long> pumpIds = pumpLocationRepository.findAllIds();
        int failed = 0;
        for (Long pumpId : pumpIds) {
            try {
                promiseService.resolvePromises(pumpId);
                followUpService.notifyDailyFollowUp(pumpId);
            } catch (RuntimeException e) {
                log.error("Credit follow-up failed for pump={}: {}", pumpId, e.getMessage(), e);
                failed++;
            }
        }
        log.info("CreditFollowUpJob complete: pumps={}, failed={}", pumpIds.size(), failed);
    }
}
