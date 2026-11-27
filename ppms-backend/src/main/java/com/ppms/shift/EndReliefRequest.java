package com.ppms.shift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** A meter reading for every nozzle the reliever covered; cashHandedBack is the cash passed back, if any. */
public record EndReliefRequest(
        @NotEmpty(message = "Meter readings are required") @Valid List<NozzleMeterReading> readings,
        @DecimalMin(value = "0", message = "Cash handed back must be >= 0") BigDecimal cashHandedBack,
        @Size(max = 500, message = "Note must be at most 500 characters") String note
) {}
