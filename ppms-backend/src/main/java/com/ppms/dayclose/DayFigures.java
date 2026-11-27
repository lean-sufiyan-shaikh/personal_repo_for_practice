package com.ppms.dayclose;

import com.ppms.attention.AttentionItem;
import com.ppms.cash.SafeCount;
import com.ppms.credit.CreditAgingResponse;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.DeliveryCheckStatus;
import com.ppms.inventory.TankerDelivery;
import com.ppms.inventory.WetStockReconciliationResponse;
import com.ppms.pump.UndergroundTank;
import com.ppms.report.DaySalesReader;
import com.ppms.shift.Shift;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Everything the checklist and the summary of one business day are built from, read once. */
record DayFigures(
        LocalDate businessDate,
        List<Shift> closedShifts,
        List<Shift> openShifts,
        Optional<SafeCount> safeCount,
        List<FuelType> fuelTypesWithoutNextDayPrice,
        Total pendingDrops,
        Total confirmedDrops,
        Total deposits,
        List<TankerDelivery> deliveries,
        Total openClaims,
        List<UndergroundTank> tanksToDip,
        Set<Long> dippedTankIds,
        WetStockReconciliationResponse reconciliation,
        DaySalesReader.DaySales sales,
        BigDecimal collections,
        CreditAgingResponse.Summary credit,
        List<AttentionItem> attentionItems,
        boolean pastDay,
        Total pendingExpenses,
        Total pendingCreditPayments) {

    record Total(long count, BigDecimal amount) {}

    List<UndergroundTank> tanksWithoutDip() {
        return tanksToDip.stream().filter(tank -> !dippedTankIds.contains(tank.getId())).toList();
    }

    long uncheckedDeliveryCount() {
        return deliveries.stream()
                .filter(delivery -> delivery.getCheckStatus() == DeliveryCheckStatus.NOT_CHECKED)
                .count();
    }
}
