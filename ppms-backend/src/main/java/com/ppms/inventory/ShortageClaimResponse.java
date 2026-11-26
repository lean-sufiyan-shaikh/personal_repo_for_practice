package com.ppms.inventory;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** nozzleReadings and pump are filled only when one claim is fetched, for its printable claim note. */
@Builder
public record ShortageClaimResponse(
        Long id,
        Long deliveryId,
        Long tankId,
        String tankIdentifier,
        String fuelType,
        String invoiceReference,
        OffsetDateTime deliveryDate,
        String tankerName,
        BigDecimal invoiceLitres,
        BigDecimal receivedLitres,
        BigDecimal shortLitres,
        BigDecimal shortPercent,
        BigDecimal tolerancePercent,
        BigDecimal pricePerLitre,
        BigDecimal claimAmount,
        BigDecimal dipBeforeLitres,
        BigDecimal dipAfterLitres,
        BigDecimal soldBetweenLitres,
        BigDecimal observedDensity,
        BigDecimal invoiceDensity,
        BigDecimal densityDifference,
        BigDecimal densityTolerance,
        boolean densityFlagged,
        String status,
        OffsetDateTime raisedAt,
        String raisedByUserName,
        BigDecimal creditNoteAmount,
        String creditNoteReference,
        LocalDate creditNoteDate,
        String rejectionReason,
        OffsetDateTime resolvedAt,
        String resolvedByUserName,
        List<NozzleReadingLine> nozzleReadings,
        PumpDetails pump
) {

    public record NozzleReadingLine(Long nozzleId, Integer nozzleNumber, String duName, BigDecimal readingBefore,
                                    BigDecimal readingAfter, BigDecimal litresSold) {
    }

    public record PumpDetails(String name, String address, String gstin, String legalName) {
    }
}
