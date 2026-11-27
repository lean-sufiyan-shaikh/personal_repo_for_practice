package com.ppms.monthclose;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** Request bodies of the month close endpoints. */
public final class MonthCloseRequests {

    private MonthCloseRequests() {
    }

    /** exceptions name the days closed with the month although they were not approved, each with its reason. */
    public record Close(@Valid List<ExceptionDay> exceptions) {

        public List<ExceptionDay> exceptionsOrEmpty() {
            return exceptions == null ? List.of() : exceptions;
        }
    }

    public record ExceptionDay(
            @NotNull(message = "date is required")
            LocalDate date,
            @NotBlank(message = "reason is required")
            @Size(max = 300, message = "reason must be at most 300 characters")
            String reason) {}

    public record Reopen(
            @NotBlank(message = "Reason is required")
            @Size(max = 500, message = "Reason must be at most 500 characters")
            String reason) {}
}
