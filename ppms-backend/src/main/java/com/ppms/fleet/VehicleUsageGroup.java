package com.ppms.fleet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The credit an account's vehicle took in a period, or with a null vehicle the credit for unknown or unrecorded
 * vehicles. Litres count fuel only; the amount counts fuel and counter sales.
 */
public record VehicleUsageGroup(Long vehicleId, String registration, BigDecimal litres, BigDecimal amount,
                                List<Line> lines) {

    /**
     * @param item                the fuel type of a fuel entry, the product of a counter sale
     * @param enteredRegistration the registration as written on the entry; may differ from a vehicle's only in spelling
     * @param litres              null for a counter sale or a fuel entry without a known price
     */
    public record Line(LocalDate date, String accountName, String item, String enteredRegistration,
                       String driverName, BigDecimal litres, BigDecimal amount) {}

    public boolean isUnassigned() {
        return vehicleId == null;
    }
}
