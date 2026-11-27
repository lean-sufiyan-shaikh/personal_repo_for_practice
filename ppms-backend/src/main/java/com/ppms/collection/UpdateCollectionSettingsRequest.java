package com.ppms.collection;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Replaces all collection settings at once.
 *
 * @param creditHoldDays null turns the credit hold off
 * @param upiId          blank, together with upiPayeeName, removes the UPI details
 * @param creditPaymentApprovalThreshold null lets a manager's credit payment of any amount through
 */
public record UpdateCollectionSettingsRequest(
        @NotNull(message = "Reminder stages are required") List<Integer> reminderStageDays,
        Integer creditHoldDays,
        String upiId,
        String upiPayeeName,
        BigDecimal creditPaymentApprovalThreshold,
        @NotNull(message = "Back-dated payment days are required") Integer backdatedPaymentApprovalDays
) {}
