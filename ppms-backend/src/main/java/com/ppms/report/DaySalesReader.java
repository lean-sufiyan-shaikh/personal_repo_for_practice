package com.ppms.report;

import com.ppms.fuel.FuelType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Fuel and counter product sales of one business day, read the same way the DAY balance sheet reads them, so a day
 * close summary and the day's balance sheet show the same figures.
 */
@Component
@RequiredArgsConstructor
public class DaySalesReader {

    private final BalanceSheetInputsCollector inputs;
    private final BalanceSheetSupportService supportService;

    public record FuelLine(FuelType fuelType, BigDecimal litres, BigDecimal revenue, BigDecimal cost) {}

    public record ProductTotals(BigDecimal revenue, BigDecimal cost) {
        static final ProductTotals NONE = new ProductTotals(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }

    public record DaySales(List<FuelLine> fuels, ProductTotals products) {}

    @Transactional(readOnly = true)
    public DaySales salesOf(Long pumpId, LocalDate date, List<Long> closedShiftIds) {
        BalanceSheetInputsCollector.FuelSales fuelSales = inputs.sumFuelSales(closedShiftIds);
        BalanceSheetInputsCollector.CostOfGoods costOfGoods = inputs.sumCostOfGoods(closedShiftIds);
        Set<FuelType> fuelTypes = EnumSet.noneOf(FuelType.class);
        fuelTypes.addAll(fuelSales.litresByFuel().keySet());
        fuelTypes.addAll(costOfGoods.byFuel().keySet());
        List<FuelLine> fuels = new ArrayList<>();
        for (FuelType fuelType : fuelTypes) {
            fuels.add(new FuelLine(fuelType,
                    fuelSales.litresByFuel().getOrDefault(fuelType, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP),
                    fuelSales.expectedRevenueByFuel().getOrDefault(fuelType, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                    costOfGoods.byFuel().getOrDefault(fuelType, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)));
        }
        ReportPeriod period = ReportPeriod.ofBusinessDay(date, supportService.startOfBusinessDay(date),
                supportService.startOfBusinessDay(date.plusDays(1)), supportService.startOfBusinessDay(date.plusDays(1)));
        BalanceSheetDetailResponse.ProductSalesSummary products = supportService.buildProductSalesSummary(pumpId, period);
        return new DaySales(fuels, products == null
                ? ProductTotals.NONE
                : new ProductTotals(products.getTotalRevenue(), products.getTotalCogs()));
    }
}
