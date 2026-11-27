package com.ppms.collection;

import com.ppms.pump.PumpLocation;

import java.util.Arrays;
import java.util.List;

/**
 * @param reminderStageDays days overdue at which a reminder is due, ascending; empty when reminders are off
 * @param creditHoldDays    days overdue at which credit is held; null when off
 * @param upiId             null, with upiPayeeName, when the pump has no UPI details
 */
public record CollectionSettingsResponse(List<Integer> reminderStageDays, Integer creditHoldDays, String upiId,
                                         String upiPayeeName) {

    static CollectionSettingsResponse of(PumpLocation pump) {
        return new CollectionSettingsResponse(Arrays.asList(pump.getReminderStageDays()), pump.getCreditHoldDays(),
                pump.getUpiId(), pump.getUpiPayeeName());
    }
}
