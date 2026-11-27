package com.ppms.pump;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class PumpResponse {

    private Long id;
    private String name;
    private String address;
    private Integer maxDuCount;
    private Long ownerId;
    private OffsetDateTime createdAt;
    private BigDecimal discrepancyEscalationThreshold;
    private BigDecimal expenseApprovalThreshold;
    private String gstin;
    private String legalName;
    private String stateName;
    private String invoicePrefix;
    private BigDecimal receiptTolerancePercent;
    private BigDecimal densityTolerance;
    private BigDecimal safeCountTolerance;
    private LocalTime safeCountCutoffTime;
    private boolean demo;
    private Integer earlyStartGraceMinutes;
    private BigDecimal roundingTolerance;
    private BigDecimal testVolumeLitres;
    private BigDecimal maxTestLitresPerNozzle;
    /** Active Dispensary Units with their nozzles. */
    private List<DUResponse> dus;
}
