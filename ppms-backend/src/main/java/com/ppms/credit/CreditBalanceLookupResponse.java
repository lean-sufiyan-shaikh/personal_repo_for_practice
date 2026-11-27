package com.ppms.credit;

import java.math.BigDecimal;

/** @param upiPayLink upi://pay link for the outstanding amount; null when the pump has no UPI details or nothing is owed */
public record CreditBalanceLookupResponse(BigDecimal outstandingAmount, BigDecimal creditLimit, String upiPayLink) {
}
