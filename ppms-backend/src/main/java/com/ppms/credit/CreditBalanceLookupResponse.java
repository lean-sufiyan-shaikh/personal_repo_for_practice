package com.ppms.credit;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * @param outstandingAmount the whole group's balance for a main account; a sub-account's own usage only
 * @param creditLimit       the group's limit; null when unlimited, and for a sub-account
 * @param subAccount        the balance and limit are held on the main account, not on this one
 * @param upiPayLink        upi://pay link for the group balance; null for a sub-account, when the pump has no UPI
 *                          details or nothing is owed
 * @param vehicleUsage      this calendar month and the one before, newest first
 */
public record CreditBalanceLookupResponse(BigDecimal outstandingAmount, BigDecimal creditLimit, boolean unlimited,
                                          boolean subAccount, String upiPayLink, List<VehicleMonth> vehicleUsage) {

    public record VehicleMonth(YearMonth month, List<VehicleTotal> vehicles) {}

    /** @param registration null for credit given to unknown or unrecorded vehicles */
    public record VehicleTotal(String registration, BigDecimal litres, BigDecimal amount, int entryCount) {}
}
