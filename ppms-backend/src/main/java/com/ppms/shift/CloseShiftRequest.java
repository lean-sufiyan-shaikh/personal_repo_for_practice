package com.ppms.shift;

import com.ppms.cash.CashDenominations;
import com.ppms.fleet.VehicleCreditInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class CloseShiftRequest {

    /**
     * End meter reading for each nozzle in the shift.
     * Backend validates that every nozzle that was opened has an end reading provided.
     */
    @NotEmpty(message = "At least one fuel reading is required")
    @Valid
    private List<OutletEndReadingRequest> fuelReadings;

    // Payment breakdown — all must be >= 0
    // Cash counted in the bag at close; confirmed cash drops are added on the server to give the shift's cash.
    @NotNull(message = "Cash collected is required")
    @DecimalMin(value = "0", message = "Cash collected must be >= 0")
    private BigDecimal cashCollected;

    /** The bag count by note, when it was counted that way; must add up to cashCollected. Null when only a total was typed. */
    @Valid
    private CashDenominations cashDenominations;

    @NotNull(message = "UPI collected is required")
    @DecimalMin(value = "0", message = "UPI collected must be >= 0")
    private BigDecimal upiCollected;

    @NotNull(message = "Card collected is required")
    @DecimalMin(value = "0", message = "Card collected must be >= 0")
    private BigDecimal cardCollected;

    @NotNull(message = "Fleet card collected is required")
    @DecimalMin(value = "0", message = "Fleet card collected must be >= 0")
    private BigDecimal fleetCardCollected;

    // Credit total — derived from creditEntries; still sent for quick backend validation
    @NotNull(message = "Credit total is required")
    @DecimalMin(value = "0", message = "Credit total must be >= 0")
    private BigDecimal creditTotal;

    @Valid
    private List<CreditEntryRequest> creditEntries = new ArrayList<>();

    // Pre-recorded credit entries the operator corrected; they are voided in the same transaction as the close.
    private List<Long> replacedCreditEntryIds = new ArrayList<>();

    private String discrepancyReason;

    /** Owner/Admin only: lets credit for over-limit, on-hold or overdue clients through, recorded with the reason. */
    private Boolean allowBlockedCredit;

    private String blockedCreditReason;

    /** Why test litres on a nozzle went above the pump's limit; required only then. */
    @Size(max = 500, message = "Test litres note must be at most 500 characters")
    private String testLitresNote;

    // ── Inner records ────────────────────────────────────────────────────────

    /** testLitres left out means none at close, and keeps the recorded test litres on an amendment. */
    public record OutletEndReadingRequest(
            @NotNull(message = "Nozzle ID is required") Long nozzleId,
            @NotNull(message = "End reading is required") BigDecimal endReading,
            @DecimalMin(value = "0", message = "Test litres must be >= 0") BigDecimal testLitres
    ) {

        public OutletEndReadingRequest(Long nozzleId, BigDecimal endReading) {
            this(nozzleId, endReading, null);
        }
    }

    public record CreditEntryRequest(
            @NotBlank(message = "Client name is required") String clientName,
            /** Optional client ID — preferred over name-based lookup when a sub-account is selected. */
            Long clientId,
            String billNo,
            @NotNull(message = "Amount is required")
            @DecimalMin(value = "0.01", message = "Credit entry amount must be greater than zero")
            BigDecimal amount,
            String fuelType,
            String description,
            /** Optional vehicle registration number typed in when the vehicle is not picked, e.g. "MH12AB1234" */
            @Size(max = 30, message = "Vehicle registration must be at most 30 characters")
            String vehicleRegistration,
            /** Optional driver who filled; defaults to the picked vehicle's driver */
            @Size(max = 100, message = "Driver name must be at most 100 characters")
            String driverName,
            /** Optional — the client's approved vehicle; preferred over vehicleRegistration */
            Long vehicleId,
            @Pattern(regexp = "\\d{10}", message = "Driver phone must be exactly 10 digits")
            String driverPhone
    ) {

        public VehicleCreditInput vehicleInput() {
            return new VehicleCreditInput(vehicleId, vehicleRegistration, driverName, driverPhone);
        }
    }
}
