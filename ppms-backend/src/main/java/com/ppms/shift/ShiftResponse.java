package com.ppms.shift;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ppms.fuel.FuelType;
import com.ppms.cash.CashDenominations;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class ShiftResponse {

    private Long id;
    private Long pumpId;

    // ── DU info ──────────────────────────────────────────────────────────────
    private Long duId;
    private Integer duNumber;
    private String duName;

    /** Nozzles from this DU that are active in this shift. */
    private List<NozzleSummary> nozzles;

    private Long operatorId;
    private String operatorName;
    private String openedByUserName;

    private String shiftWindow;
    private LocalDate shiftDate;
    private OffsetDateTime actualStartTime;
    private OffsetDateTime actualEndTime;
    /** When the closing readings were really entered; actualEndTime is capped at the scheduled end. */
    private OffsetDateTime closedAt;
    private Long closedByUserId;

    /**
     * Scheduled end time derived from the linked shift definition.
     * Null only when no shift definition is linked (legacy/orphaned shifts).
     * Used by the close-shift UI to warn the operator when they are closing late
     * and to show them the time that will be recorded (definition end, not clock time).
     */
    private OffsetDateTime scheduledEndTime;

    /** Per-nozzle fuel readings (one per nozzle in this shift). */
    private List<FuelReadingResponse> fuelReadings;

    private BigDecimal totalAmountDue;

    // Payment breakdown
    private BigDecimal cashCollected;
    private BigDecimal upiCollected;
    private BigDecimal cardCollected;
    private BigDecimal fleetCardCollected;
    private BigDecimal creditTotal;
    /** Confirmed cash drops, already included in cashCollected; null before the shift closes. */
    private BigDecimal cashDropped;
    private CashDenominations cashDenominations;

    // Discrepancy
    private BigDecimal discrepancyAmount;
    private String discrepancyType;
    private String discrepancyReason;
    private String discrepancyResolution;
    private String discrepancyResolutionNote;
    private String investigationNote;
    private String investigatedByName;
    private OffsetDateTime investigationStartedAt;
    /** Every investigation note, oldest first. */
    private List<InvestigationNoteResponse> investigationHistory;
    /** Cash handed back for a SHORT discrepancy, all of it or part; null when none. */
    private BigDecimal cashRecoveryAmount;
    private OffsetDateTime cashRecoveredAt;
    /** Collected minus due absorbed as rounding when the shift closed balanced; null when there was none. */
    private BigDecimal roundingAdjustment;
    /** True when the tanks' book stock could not cover what the shift sold; a delivery may be missing. */
    private boolean stockShortfall;

    private String status;

    /** True if this shift was entered retroactively via the Admin backfill flow. */
    @JsonProperty("isBackfilled")
    private boolean isBackfilled;

    private List<CreditEntryResponse> creditEntries;

    // ── Inner records ─────────────────────────────────────────────────────────

    public record NozzleSummary(
            Long id,
            Integer nozzleNumber,
            String fuelType
    ) {}

    /** priceSegments is empty unless the price changed while the shift was open; salesAmount is set at close. */
    public record FuelReadingResponse(
            Long nozzleId,
            FuelType fuelType,
            BigDecimal startReading,
            BigDecimal endReading,
            BigDecimal priceSnapshot,
            BigDecimal unitsSold,
            BigDecimal maxMeterValue,
            BigDecimal salesAmount,
            BigDecimal stockShortfallLitres,
            List<PriceSegmentResponse> priceSegments
    ) {}

    /** Litres between two meter readings sold at one price; unitsSold is null until the shift closes. */
    public record PriceSegmentResponse(
            BigDecimal startReading,
            BigDecimal pricePerUnit,
            OffsetDateTime startsAt,
            BigDecimal unitsSold
    ) {}

    public record InvestigationNoteResponse(
            String note,
            String writtenByName,
            OffsetDateTime writtenAt
    ) {}

    public record CreditEntryResponse(
            Long id,
            Long clientId,
            String clientName,
            String billNo,
            BigDecimal amount,
            String fuelType,
            String description,
            String voidStatus,
            String voidReason,
            String vehicleRegistration,
            String driverName,
            Long vehicleId,
            String driverPhone,
            BigDecimal litres,
            /* UNKNOWN_VEHICLE, FUEL_NOT_ALLOWED or OVER_MONTHLY_LIMIT when a WARN account's entry was let through */
            String vehicleFlag
    ) {}
}
