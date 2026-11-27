package com.ppms.shift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The corrected closing figures of a closed shift. cashCollected is the bag count, as at close: confirmed drops are
 * added on the server. Credit sales are corrected through their own entries, not here.
 */
public record AmendShiftRequest(
        @NotEmpty(message = "At least one fuel reading is required")
        @Valid
        List<CloseShiftRequest.OutletEndReadingRequest> fuelReadings,

        @NotNull(message = "Cash collected is required")
        @DecimalMin(value = "0", message = "Cash collected must be >= 0")
        BigDecimal cashCollected,

        @NotNull(message = "UPI collected is required")
        @DecimalMin(value = "0", message = "UPI collected must be >= 0")
        BigDecimal upiCollected,

        @NotNull(message = "Card collected is required")
        @DecimalMin(value = "0", message = "Card collected must be >= 0")
        BigDecimal cardCollected,

        @NotNull(message = "Fleet card collected is required")
        @DecimalMin(value = "0", message = "Fleet card collected must be >= 0")
        BigDecimal fleetCardCollected,

        @NotBlank(message = "Reason is required")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
