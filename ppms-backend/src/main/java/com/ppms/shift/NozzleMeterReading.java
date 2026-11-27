package com.ppms.shift;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record NozzleMeterReading(
        @NotNull(message = "Nozzle ID is required") Long nozzleId,
        @NotNull(message = "Meter reading is required")
        @DecimalMin(value = "0", message = "Meter reading must be >= 0") BigDecimal meterReading
) {}
