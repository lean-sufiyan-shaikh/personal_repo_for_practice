package com.ppms.collection;

import com.ppms.pump.PumpLocation;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * @param reminderStageDays days overdue at which a reminder is due, ascending; empty when reminders are off
 * @param creditHoldDays    days overdue at which credit is held; null when off
 * @param upiId             null, with upiPayeeName, when the pump has no UPI details
 * @param creditPaymentApprovalThreshold a manager's payment above it waits for approval; null when any amount passes
 * @param backdatedPaymentApprovalDays   a manager's payment dated more days back than this waits for approval
 */
public record CollectionSettingsResponse(List<Integer> reminderStageDays, Integer creditHoldDays, String upiId,
                                         String upiPayeeName, BigDecimal creditPaymentApprovalThreshold,
                                         int backdatedPaymentApprovalDays) {

    static CollectionSettingsResponse of(PumpLocation pump) {
        return new CollectionSettingsResponse(Arrays.asList(pump.getReminderStageDays()), pump.getCreditHoldDays(),
                pump.getUpiId(), pump.getUpiPayeeName(), pump.getCreditPaymentApprovalThreshold(),
                pump.getBackdatedPaymentApprovalDays());
    }
}
