package com.ppms.pump;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateTankRequest {

    @NotNull(message = "Capacity is required")
    @DecimalMin(value = "0.001", message = "Capacity must be greater than 0")
    private BigDecimal capacity;

    // Optional — if blank the existing identifier is kept
    private String tankIdentifier;

    // Optional — if null the existing alert limit is kept
    @DecimalMin(value = "0.01", message = "Loss alert limit must be at least 0.01%")
    @DecimalMax(value = "100", message = "Loss alert limit cannot be more than 100%")
    @Digits(integer = 3, fraction = 2, message = "Loss alert limit can have at most 2 decimals")
    private BigDecimal lossAlertPercent;
}
