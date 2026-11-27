package com.ppms.monthclose;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The owner's record of a closed month, stored unchanged with each close. Sales, margins and the payment mix come
 * from the month's closed shifts and counter sales, costs from the profit and loss of the month; otherCosts holds any
 * further operating cost that report deducts beyond those named. Credit outstanding is as it stood at the close.
 */
public record MonthSummary(String month, LocalDate fromDate, LocalDate toDate, OffsetDateTime builtAt,
                           int businessDayCount, int approvedDayCount, List<ExceptionDay> exceptions,
                           List<FuelLine> fuels, BigDecimal fuelLitres, BigDecimal fuelRevenue, BigDecimal fuelCost,
                           BigDecimal fuelMargin, BigDecimal productRevenue, BigDecimal productCost,
                           BigDecimal productMargin, Payments payments, BigDecimal grossProfit, Costs costs,
                           BigDecimal netProfit, Credit credit, BigDecimal safeClosing, BigDecimal pettyCashClosing,
                           List<TankClosing> tanks) {

    /** A business day closed with the month although it was not approved; statusCode is its day-close state then. */
    public record ExceptionDay(LocalDate date, String statusCode, String reason) {}

    public record FuelLine(String fuelType, BigDecimal litres, BigDecimal revenue, BigDecimal cost, BigDecimal margin) {}

    public record Payments(BigDecimal cash, BigDecimal upi, BigDecimal card, BigDecimal fleetCard, BigDecimal credit,
                           BigDecimal total) {}

    public record Costs(BigDecimal expenses, BigDecimal payroll, BigDecimal bankCharges, BigDecimal chargebacks,
                        BigDecimal badDebt, BigDecimal otherCosts, BigDecimal total) {}

    public record Credit(BigDecimal given, BigDecimal collected, BigDecimal outstandingAtClose) {}

    /** The last dip of the month; null figures when the tank was not dipped that month. */
    public record TankClosing(Long tankId, String tankIdentifier, String fuelType, LocalDate lastDipDate,
                              BigDecimal closingLitres, BigDecimal monthVarianceLitres) {}
}
