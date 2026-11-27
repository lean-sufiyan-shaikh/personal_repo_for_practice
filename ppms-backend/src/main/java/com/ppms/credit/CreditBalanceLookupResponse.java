package com.ppms.credit;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * @param upiPayLink   upi://pay link for the outstanding amount; null when the pump has no UPI details or nothing is owed
 * @param vehicleUsage this calendar month and the one before, newest first
 */
public record CreditBalanceLookupResponse(BigDecimal outstandingAmount, BigDecimal creditLimit, String upiPayLink,
                                          List<VehicleMonth> vehicleUsage) {

    public record VehicleMonth(YearMonth month, List<VehicleTotal> vehicles) {}

    /** @param registration null for credit given to unknown or unrecorded vehicles */
    public record VehicleTotal(String registration, BigDecimal litres, BigDecimal amount, int entryCount) {}
}
