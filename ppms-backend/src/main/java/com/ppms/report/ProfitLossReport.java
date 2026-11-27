package com.ppms.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProfitLossReport(
        Long pumpId,
        LocalDate from,
        LocalDate to,
        int totalShifts,
        BigDecimal totalRevenue,
        BigDecimal totalCogs,
        BigDecimal grossProfit,
        BigDecimal totalCashRevenue,
        BigDecimal totalUpiRevenue,
        BigDecimal totalCardRevenue,
        BigDecimal totalCreditRevenue,
        BigDecimal totalFleetCardRevenue,
        /** Cash + UPI + card + fleet card + credit of the closed shifts. */
        BigDecimal totalCollected,
        /** Expenses dated in the period and approved. */
        BigDecimal approvedExpenses,
        /** MDR and GST on MDR of settlements that reached the bank in the period. */
        BigDecimal bankCharges,
        /** grossProfit − approvedExpenses − bankCharges; payroll is not included. */
        BigDecimal netOperatingProfit,
        List<FuelLine> byFuelType,
        List<DayLine> byDay) {

    public record FuelLine(
            String fuelType,
            BigDecimal revenue,
            BigDecimal cogs,
            BigDecimal grossProfit,
            BigDecimal grossMarginPerLitre,    // null when totalUnitsSold == 0
            List<LotCostLine> lotCostLines) {}

    /** One business day that had a closed shift: revenue and cost of goods of that day's shifts. */
    public record DayLine(
            LocalDate date,
            BigDecimal revenue,
            BigDecimal cogs,
            BigDecimal grossProfit) {}

    public record LotCostLine(
            Long lotId,
            String tankerReference,
            LocalDate deliveryDate,
            BigDecimal costPricePerUnit,
            BigDecimal quantityConsumed,
            BigDecimal lotCost) {}
}
