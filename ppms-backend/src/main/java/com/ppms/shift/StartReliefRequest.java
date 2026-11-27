package com.ppms.shift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** A meter reading for every nozzle the relieved operator works; cashHandedOver is the cash passed to the reliever, if any. */
public record StartReliefRequest(
        @NotNull(message = "Operator going on break is required") Long relievedOperatorId,
        @NotNull(message = "Relief operator is required") Long reliefOperatorId,
        @NotEmpty(message = "Meter readings are required") @Valid List<NozzleMeterReading> readings,
        @DecimalMin(value = "0", message = "Cash handed over must be >= 0") BigDecimal cashHandedOver,
        @Size(max = 500, message = "Note must be at most 500 characters") String note
) {}
