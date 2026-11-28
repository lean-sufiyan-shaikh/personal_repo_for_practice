package com.ppms.inventory;

import com.ppms.common.time.BusinessClock;
import com.ppms.inventory.WetStockReconciliationResponse.Summary;
import com.ppms.inventory.WetStockReconciliationResponse.SummaryStatus;
import com.ppms.inventory.WetStockReconciliationResponse.TankReconciliation;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WetStockLossAlertServiceTest {

    private static final Long PUMP_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private WetStockReconciliationService reconciliationService;
    @Mock private NotificationDedupService dedupService;
    @Mock private BusinessClock businessClock;
    @InjectMocks private WetStockLossAlertService alertService;

    @Test
    void onlyATankWithALossAboveItsLimitRaisesAnAlertForTheLastSevenDaysBeforeToday() {
        when(businessClock.today()).thenReturn(TODAY);
        when(reconciliationService.reconcile(PUMP_ID, TODAY.minusDays(7), TODAY.minusDays(1), null))
                .thenReturn(new WetStockReconciliationResponse(TODAY.minusDays(7), TODAY.minusDays(1), List.of(
                        tank(3L, SummaryStatus.LOSS_ABOVE_LIMIT, "-20", "-1.00", "0.50"),
                        tank(4L, SummaryStatus.WITHIN_LIMITS, "-5", "-0.25", "0.50"),
                        tank(5L, SummaryStatus.GAIN_ABOVE_LIMIT, "30", "1.50", "0.50"))));

        int tanksAboveLimit = alertService.raiseLossAlerts(PUMP_ID);

        assertThat(tanksAboveLimit).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(dedupService).insertDaily(eq(PUMP_ID), eq(NotificationType.WET_STOCK_LOSS), eq("WET_STOCK_LOSS:tank:3"),
                eq(TODAY), anyString(), anyString(), params.capture());
        assertThat(params.getValue())
                .containsEntry("lossLitres", "20")
                .containsEntry("lossPercent", "1.00")
                .containsEntry("limitPercent", "0.5");
    }

    @Test
    void noTankAboveItsLimitRaisesNothing() {
        when(businessClock.today()).thenReturn(TODAY);
        when(reconciliationService.reconcile(any(), any(), any(), any()))
                .thenReturn(new WetStockReconciliationResponse(TODAY.minusDays(7), TODAY.minusDays(1), List.of(
                        tank(4L, SummaryStatus.WITHIN_LIMITS, "-5", "-0.25", "1.5"))));

        assertThat(alertService.raiseLossAlerts(PUMP_ID)).isZero();
        verify(dedupService, never())
                .insertDaily(any(), any(), anyString(), any(), anyString(), anyString(), any());
    }

    private static TankReconciliation tank(Long tankId, SummaryStatus status, String varianceLitres,
                                           String variancePercent, String lossAlertPercent) {
        return new TankReconciliation(tankId, "T-" + tankId, "PETROL", new BigDecimal("50"), new BigDecimal(lossAlertPercent),
                new Summary(new BigDecimal("2000"), new BigDecimal(varianceLitres), new BigDecimal(variancePercent), 1, 0, status),
                List.of());
    }
}
