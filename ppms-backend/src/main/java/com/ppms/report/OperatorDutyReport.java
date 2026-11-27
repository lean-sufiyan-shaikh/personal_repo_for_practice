package com.ppms.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record OperatorDutyReport(
        Long pumpId,
        Long operatorId,
        String operatorName,
        LocalDate from,
        LocalDate to,
        int totalShifts,
        BigDecimal totalAmountDue,
        BigDecimal totalDiscrepancy,
        List<ShiftLine> shifts) {

    public record ShiftLine(
            Long shiftId,
            LocalDate shiftDate,
            String shiftWindow,
            java.time.OffsetDateTime actualStartTime,
            java.time.OffsetDateTime actualEndTime,
            /* When the closing readings were really entered; actualEndTime is capped at the scheduled end. */
            java.time.OffsetDateTime closedAt,
            BigDecimal totalAmountDue,
            BigDecimal cashCollected,
            BigDecimal upiCollected,
            BigDecimal cardCollected,
            BigDecimal creditTotal,
            BigDecimal discrepancyAmount,
            String discrepancyType,
            String status) {}
}
