package com.ppms.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** A limit of 0 allows no credit; unlimited lifts the limit altogether. */
@Data
public class UpdateCreditLimitRequest {

    @NotNull(message = "Credit limit is required")
    @DecimalMin(value = "0", message = "Credit limit cannot be negative")
    private BigDecimal creditLimit;

    private boolean unlimited;

    @NotBlank(message = "Give a reason for the change")
    @Size(max = 500, message = "Reason must be at most 500 characters")
    private String reason;
}
