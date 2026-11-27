package com.ppms.credit;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Checks shared by the credit desk actions: a reason is given, amounts are in rupees and paise, the account is open. */
final class CreditDeskRules {

    private CreditDeskRules() {
    }

    static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCodes.CREDIT_REASON_REQUIRED, Map.of(), "Give a reason.");
        }
        return reason.trim();
    }

    static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    static void requireOpenAccount(CreditClient client) {
        if (client.isClosed()) {
            throw new BusinessException(ErrorCodes.CREDIT_ACCOUNT_CLOSED, Map.of("clientName", client.getName()),
                    "The account of '" + client.getName() + "' is closed. Reopen it first.");
        }
    }

    /** Refuses an amount above what is available, naming both in rupees. */
    static void requireAtMost(BigDecimal amount, BigDecimal available, String code, String message) {
        if (amount.compareTo(available) > 0) {
            throw new BusinessException(code,
                    Map.of("amount", money(amount).toPlainString(), "available", money(available).toPlainString()),
                    message + " (₹" + money(amount).toPlainString() + " asked, ₹" + money(available).toPlainString()
                            + " available).");
        }
    }
}
