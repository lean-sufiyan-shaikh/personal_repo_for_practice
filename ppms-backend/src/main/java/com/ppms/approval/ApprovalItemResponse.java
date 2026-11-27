package com.ppms.approval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One expense, credit payment or forward meter adjustment waiting for an Owner or Admin.
 *
 * @param amount          for a meter adjustment, the unbilled litres at the day's price
 * @param category        expense category, or fuel type of a meter adjustment; null for a credit payment
 * @param clientId        credit client paying; null for an expense
 * @param blockedReason   why the current user cannot decide this item; null when they can
 * @param blockedReasonCode names blockedReason for translated screens, filled with blockedReasonParams
 * @param litres          unbilled litres of a meter adjustment; null for other items
 * @param nozzleLabel     DU and nozzle of a meter adjustment, e.g. "DU 1 · Nozzle 2"; null for other items
 */
public record ApprovalItemResponse(
        ApprovalKind kind,
        Long id,
        BigDecimal amount,
        LocalDate itemDate,
        String submittedByName,
        OffsetDateTime submittedAt,
        String category,
        Long clientId,
        String clientName,
        String note,
        String blockedReason,
        String blockedReasonCode,
        Map<String, Object> blockedReasonParams,
        BigDecimal litres,
        String nozzleLabel
) {}
