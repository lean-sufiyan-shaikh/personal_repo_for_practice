package com.ppms.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreditLimitSettingsRequest(
        @NotNull(message = "Default credit limit is required")
        @DecimalMin(value = "0", message = "Default credit limit cannot be negative")
        BigDecimal defaultCreditLimit,
        @NotNull(message = "Manager limit cap is required")
        @DecimalMin(value = "0", message = "Manager limit cap cannot be negative")
        BigDecimal managerLimitCap,
        @NotNull(message = "Near-limit percent is required")
        @Min(value = 50, message = "Near-limit percent must be from 50 to 100")
        @Max(value = 100, message = "Near-limit percent must be from 50 to 100")
        Integer nearLimitPercent
) {}
