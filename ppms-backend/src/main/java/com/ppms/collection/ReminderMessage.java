package com.ppms.collection;

import java.math.BigDecimal;

/** What a reminder tells the client. @param upiPayLink null when the pump has no UPI details */
public record ReminderMessage(Long pumpId, Long clientId, String clientName, String phone, BigDecimal outstanding,
                              long daysOverdue, String upiPayLink) {
}
