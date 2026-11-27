package com.ppms.fuel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class SetFuelPriceRequest {

    @NotNull(message = "Fuel type is required")
    private FuelType fuelType;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be greater than zero")
    private BigDecimal pricePerUnit;

    /**
     * Must be explicitly set to true when the new price deviates >15% from the last price
     * (spec Business Rule 37). The frontend shows a warning dialog and re-submits with confirmed=true.
     * Defaults to false.
     */
    private boolean confirmed = false;

    /**
     * The current meter reading of every nozzle on an open shift selling this fuel. Litres up to it keep the old
     * price and litres after it are charged at the new one; required when such shifts are open.
     */
    @Valid
    private List<OpenNozzleReading> openNozzleReadings;

    public record OpenNozzleReading(
            @NotNull(message = "Nozzle ID is required") Long nozzleId,
            @NotNull(message = "Meter reading is required")
            @DecimalMin(value = "0", message = "Meter reading cannot be negative")
            BigDecimal meterReading) {
    }
}
