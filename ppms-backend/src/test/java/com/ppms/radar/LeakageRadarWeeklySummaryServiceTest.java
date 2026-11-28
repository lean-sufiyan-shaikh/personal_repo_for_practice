package com.ppms.radar;

import com.ppms.common.time.BusinessClock;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeakageRadarWeeklySummaryServiceTest {

    private static final Long PUMP_ID = 5L;
    private static final LocalDate THURSDAY = LocalDate.of(2026, 11, 26);

    private final LeakageRadarService radarService = mock(LeakageRadarService.class);
    private final NotificationDedupService dedupService = mock(NotificationDedupService.class);
    private final BusinessClock businessClock = mock(BusinessClock.class);

    private final LeakageRadarWeeklySummaryService service =
            new LeakageRadarWeeklySummaryService(radarService, dedupService, businessClock);

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(THURSDAY);
    }

    @Test
    void summaryCountsWhatIsWorthALookAndIsKeyedByTheWeeksMonday() {
        when(radarService.radar(PUMP_ID, 30)).thenReturn(radar(
                List.of(person(RadarBand.WATCH), person(RadarBand.LOW), person(RadarBand.NOT_ENOUGH_DATA)),
                List.of(nozzle(RadarBand.HIGH)), List.of(tank(RadarBand.LOW))));

        long worthALook = service.sendWeeklySummary(PUMP_ID);

        assertThat(worthALook).isEqualTo(2);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(dedupService).insertDaily(eq(PUMP_ID), eq(NotificationType.LEAKAGE_RADAR_WEEKLY), eq("LEAKAGE_RADAR_WEEKLY"),
                eq(LocalDate.of(2026, 11, 23)), anyString(), anyString(), params.capture());
        assertThat(params.getValue()).containsEntry("people", 1L).containsEntry("nozzles", 1L)
                .containsEntry("tanks", 0L).containsEntry("days", 30);
    }

    @Test
    void nothingWorthALookSendsNoSummary() {
        when(radarService.radar(PUMP_ID, 30)).thenReturn(radar(List.of(person(RadarBand.LOW)), List.of(), List.of()));

        assertThat(service.sendWeeklySummary(PUMP_ID)).isZero();
        verify(dedupService, never()).insertDaily(any(), any(), anyString(), any(), anyString(), anyString(), anyMap());
    }

    private static LeakageRadarResponse radar(List<LeakageRadarResponse.PersonScore> people,
                                              List<LeakageRadarResponse.NozzleScore> nozzles,
                                              List<LeakageRadarResponse.TankScore> tanks) {
        return new LeakageRadarResponse(30, THURSDAY.minusDays(29), THURSDAY, 5, people, nozzles, tanks);
    }

    private static LeakageRadarResponse.PersonScore person(RadarBand band) {
        return new LeakageRadarResponse.PersonScore(1L, "Ravi", "OPERATOR", 0, band, 0, 0, null, null, 0, List.of(), List.of());
    }

    private static LeakageRadarResponse.NozzleScore nozzle(RadarBand band) {
        return new LeakageRadarResponse.NozzleScore(1L, 1, "DU-1", "PETROL", 0, band, 0, 0, 0, 0, List.of(), List.of());
    }

    private static LeakageRadarResponse.TankScore tank(RadarBand band) {
        return new LeakageRadarResponse.TankScore(1L, "T-1", "PETROL", 0, band, null, null, null, List.of(), List.of(), List.of());
    }
}
