package com.ppms.ancillary;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The GST printed on a counter-product purchase bill; the bill's number is the delivery's invoice reference. */
public record PurchaseGstRequest(
        @NotBlank(message = "Supplier name is required")
        @Size(max = 150, message = "Supplier name must be at most 150 characters")
        String supplierName,

        @NotBlank(message = "Supplier GSTIN is required")
        String supplierGstin,

        @NotNull(message = "Invoice date is required")
        LocalDate invoiceDate,

        @NotNull(message = "Taxable value is required")
        @DecimalMin(value = "0.01", message = "Taxable value must be greater than 0")
        BigDecimal taxableValue,

        @NotNull(message = "GST rate is required")
        BigDecimal gstRatePercent
) {}
