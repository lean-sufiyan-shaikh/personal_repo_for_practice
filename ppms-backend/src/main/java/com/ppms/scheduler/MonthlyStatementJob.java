package com.ppms.scheduler;

import com.ppms.collection.MonthlyStatementService;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

/**
 * On the 1st, after the monthly interest run, stores every active account's statement for the month just ended.
 * Statements already stored are skipped, so a re-run only fills the gaps; each statement has its own transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyStatementJob {

    private final MonthlyStatementService statementService;
    private final PumpLocationRepository pumpLocationRepository;
    private final BusinessClock businessClock;

    @Scheduled(cron = "0 30 2 1 * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "MonthlyStatementJob", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void generateLastMonthStatements() {
        YearMonth month = YearMonth.from(businessClock.today()).minusMonths(1);
        List<Long> pumpIds = pumpLocationRepository.findAllIds();
        for (Long pumpId : pumpIds) {
            try {
                MonthlyStatementService.GenerationResult result = statementService.generateForPump(pumpId, month);
                log.info("Monthly statements for pump={} month={}: written={}, skipped={}, failedClientIds={}",
                        pumpId, month, result.written(), result.skipped(), result.failedClientIds());
            } catch (RuntimeException e) {
                log.error("Monthly statements failed for pump={} month={}: {}", pumpId, month, e.getMessage(), e);
            }
        }
    }
}
