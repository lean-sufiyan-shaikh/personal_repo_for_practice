package com.ppms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dips taken just before and just after the tanker unloaded, with the meter readings of every nozzle on the tank
 * at both moments, so fuel sold while unloading is not counted as a shortage. Densities are in kg/m³.
 */
public record ReceiptCheckRequest(
        @NotNull(message = "Dip before unloading is required")
        @DecimalMin(value = "0", message = "Dip before unloading cannot be negative")
        BigDecimal dipBeforeLitres,

        @NotNull(message = "Dip after unloading is required")
        @DecimalMin(value = "0", message = "Dip after unloading cannot be negative")
        BigDecimal dipAfterLitres,

        @Valid
        List<NozzleReading> nozzleReadings,

        @DecimalMin(value = "0.1", message = "Observed density must be greater than 0")
        BigDecimal observedDensity,

        @DecimalMin(value = "0.1", message = "Invoice density must be greater than 0")
        BigDecimal invoiceDensity
) {

    public List<NozzleReading> nozzleReadingsOrEmpty() {
        return nozzleReadings != null ? nozzleReadings : List.of();
    }

    public record NozzleReading(
            @NotNull(message = "Nozzle ID is required") Long nozzleId,
            @NotNull(message = "Meter reading before unloading is required")
            @DecimalMin(value = "0", message = "Meter reading cannot be negative")
            BigDecimal readingBefore,
            @NotNull(message = "Meter reading after unloading is required")
            @DecimalMin(value = "0", message = "Meter reading cannot be negative")
            BigDecimal readingAfter
    ) {}
}
