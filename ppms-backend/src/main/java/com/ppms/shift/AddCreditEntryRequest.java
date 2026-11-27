package com.ppms.shift;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AddCreditEntryRequest {

    @NotBlank(message = "Client name is required")
    private String clientName;

    /** Optional client ID — preferred over name-based lookup when a sub-account is selected. */
    private Long clientId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Fuel type is required")
    private String fuelType;

    // Optional — bill or invoice reference number
    private String billNo;

    // Optional — free-form note, e.g. "given to driver Ravi"
    private String description;

    // Optional — the client's approved vehicle; preferred over vehicleRegistration when both are sent
    private Long vehicleId;

    // Optional — vehicle registration number typed in when the vehicle is not picked, e.g. "MH12AB1234"
    @Size(max = 30, message = "Vehicle registration must be at most 30 characters")
    private String vehicleRegistration;

    // Optional — driver who filled; defaults to the picked vehicle's driver
    @Size(max = 100, message = "Driver name must be at most 100 characters")
    private String driverName;

    @Pattern(regexp = "\\d{10}", message = "Driver phone must be exactly 10 digits")
    private String driverPhone;
}
