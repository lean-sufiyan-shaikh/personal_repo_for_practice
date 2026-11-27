package com.ppms.fleet;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param allowedFuelTypes empty or null allows every fuel
 * @param monthlyLitreLimit  null for no litre limit
 * @param monthlyAmountLimit null for no rupee limit
 */
public record SaveVehicleRequest(
        @NotBlank(message = "Registration number is required")
        @Size(max = 20, message = "Registration number must be at most 20 characters")
        String registration,
        List<String> allowedFuelTypes,
        @DecimalMin(value = "0.01", message = "Monthly litre limit must be greater than zero")
        @Digits(integer = 8, fraction = 2, message = "Monthly litre limit must have at most 2 decimals")
        BigDecimal monthlyLitreLimit,
        @DecimalMin(value = "0.01", message = "Monthly amount limit must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Monthly amount limit must have at most 2 decimals")
        BigDecimal monthlyAmountLimit,
        @Size(max = 100, message = "Driver name must be at most 100 characters")
        @Pattern(regexp = "[^<>]*", message = "Driver name cannot contain < or >")
        String defaultDriverName,
        @Pattern(regexp = "\\d{10}", message = "Driver phone must be exactly 10 digits")
        String defaultDriverPhone
) {}
