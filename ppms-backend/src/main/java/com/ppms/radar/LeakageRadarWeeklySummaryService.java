package com.ppms.radar;

import com.ppms.common.time.BusinessClock;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The weekly radar summary: one notification per pump and week, keyed by the week's Monday, sent only when someone
 * or something over the last 30 days is in the Watch or High band. Only owners and admins receive the type.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeakageRadarWeeklySummaryService {

    static final int SUMMARY_DAYS = 30;
    static final String SERIES_KEY = "LEAKAGE_RADAR_WEEKLY";

    private final LeakageRadarService radarService;
    private final NotificationDedupService dedupService;
    private final BusinessClock businessClock;

    /** Returns how many people, nozzles and tanks are worth a look; zero sends nothing. */
    @Transactional
    public long sendWeeklySummary(Long pumpId) {
        LeakageRadarResponse radar = radarService.radar(pumpId, SUMMARY_DAYS);
        long people = radar.people().stream().filter(person -> person.band().isWorthALook()).count();
        long nozzles = radar.nozzles().stream().filter(nozzle -> nozzle.band().isWorthALook()).count();
        long tanks = radar.tanks().stream().filter(tank -> tank.band().isWorthALook()).count();
        long total = people + nozzles + tanks;
        if (total == 0) {
            return 0;
        }
        LocalDate monday = businessClock.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("people", people);
        params.put("nozzles", nozzles);
        params.put("tanks", tanks);
        params.put("days", SUMMARY_DAYS);
        dedupService.insertDaily(pumpId, NotificationType.LEAKAGE_RADAR_WEEKLY, SERIES_KEY, monday,
                "Radar: Worth a Look",
                "Over the last " + SUMMARY_DAYS + " days, " + people + " people, " + nozzles + " nozzles and " + tanks
                        + " tanks scored Watch or High. Open Radar to see why.",
                params);
        log.info("Leakage radar weekly summary: pump={} week={} people={} nozzles={} tanks={}",
                pumpId, monday, people, nozzles, tanks);
        return total;
    }
}
