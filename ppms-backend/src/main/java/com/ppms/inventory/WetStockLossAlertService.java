package com.ppms.inventory;

import com.ppms.common.time.BusinessClock;
import com.ppms.inventory.WetStockReconciliationResponse.SummaryStatus;
import com.ppms.inventory.WetStockReconciliationResponse.TankReconciliation;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 7-day loss rule: a tank whose reconciled loss over the 7 days ending yesterday is above its loss alert limit
 * (share of litres sold, 0.5% by default). Today is left out because its dips are usually not taken yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WetStockLossAlertService {

    public static final int WINDOW_DAYS = 7;

    private final WetStockReconciliationService reconciliationService;
    private final NotificationDedupService dedupService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public List<TankReconciliation> tanksWithLossAboveLimit(Long pumpId) {
        LocalDate lastDay = businessClock.today().minusDays(1);
        return reconciliationService.reconcile(pumpId, lastDay.minusDays(WINDOW_DAYS - 1L), lastDay, null).tanks().stream()
                .filter(tank -> tank.summary().status() == SummaryStatus.LOSS_ABOVE_LIMIT)
                .toList();
    }

    /** Raises one notification per tank and day; returns how many tanks are above their limit. */
    @Transactional
    public int raiseLossAlerts(Long pumpId) {
        List<TankReconciliation> tanks = tanksWithLossAboveLimit(pumpId);
        LocalDate today = businessClock.today();
        for (TankReconciliation tank : tanks) {
            String lossLitres = tank.summary().varianceLitres().negate().toPlainString();
            String lossPercent = tank.summary().variancePercent().negate().toPlainString();
            String salesLitres = tank.summary().salesLitres().toPlainString();
            String limitPercent = tank.lossAlertPercent().stripTrailingZeros().toPlainString();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tankName", tank.tankIdentifier());
            params.put("fuelTypeCode", tank.fuelType());
            params.put("days", WINDOW_DAYS);
            params.put("lossLitres", lossLitres);
            params.put("lossPercent", lossPercent);
            params.put("salesLitres", salesLitres);
            params.put("limitPercent", limitPercent);
            dedupService.insertDaily(pumpId, NotificationType.WET_STOCK_LOSS, "WET_STOCK_LOSS:tank:" + tank.tankId(), today,
                    "Fuel Loss Above Limit",
                    tank.tankIdentifier() + " (" + tank.fuelType() + ") lost " + lossLitres + " L in the last "
                            + WINDOW_DAYS + " days — " + lossPercent + "% of " + salesLitres + " L sold, above the "
                            + limitPercent + "% limit. Check for leaks, meter errors or theft.",
                    params);
            log.info("Wet-stock loss above limit: pump={} tank={} lossLitres={} lossPercent={} limitPercent={}",
                    pumpId, tank.tankId(), lossLitres, lossPercent, limitPercent);
        }
        return tanks.size();
    }
}
