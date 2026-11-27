package com.ppms.collection;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordPromiseRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be more than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,
        @NotNull(message = "Promised date is required") LocalDate promisedDate,
        @Size(max = 500, message = "Note must be at most 500 characters") String note
) {}
