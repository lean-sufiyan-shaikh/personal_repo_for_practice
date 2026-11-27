package com.ppms.supplier;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class UpsertSupplierRequest {

    @NotBlank(message = "name is required")
    private String name;

    private String contactName;
    private String phone;
    private String email;
    private String notes;

    /** Left out to keep the current opening balance; a non-zero balance needs the date it stood on. */
    @DecimalMin(value = "-99999999.99", message = "Opening balance is too low")
    @DecimalMax(value = "99999999.99", message = "Opening balance is too high")
    private BigDecimal openingBalance;

    private LocalDate openingBalanceDate;
}
