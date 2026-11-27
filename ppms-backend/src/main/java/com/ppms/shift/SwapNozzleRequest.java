package com.ppms.shift;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** meterReading is the nozzle's meter at the moment it changes hands; litres before it stay with the earlier operator. */
public record SwapNozzleRequest(
        @NotNull(message = "Nozzle ID is required") Long nozzleId,
        @NotNull(message = "Operator is required") Long toOperatorId,
        @NotNull(message = "Meter reading is required")
        @DecimalMin(value = "0", message = "Meter reading must be >= 0") BigDecimal meterReading,
        @Size(max = 500, message = "Note must be at most 500 characters") String note
) {}
