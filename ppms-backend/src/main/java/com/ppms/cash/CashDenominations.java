package com.ppms.cash;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * How many of each note in circulation were counted, plus the coins as one amount. The ₹2000 note is left out:
 * it was withdrawn from circulation in 2023 and banks no longer take it over the counter.
 */
public record CashDenominations(
        @NotNull @Min(0) @Max(100_000) Integer notes500,
        @NotNull @Min(0) @Max(100_000) Integer notes200,
        @NotNull @Min(0) @Max(100_000) Integer notes100,
        @NotNull @Min(0) @Max(100_000) Integer notes50,
        @NotNull @Min(0) @Max(100_000) Integer notes20,
        @NotNull @Min(0) @Max(100_000) Integer notes10,
        @NotNull @DecimalMin("0") @DecimalMax("1000000") BigDecimal coins
) {

    public BigDecimal total() {
        long notesTotal = 500L * notes500 + 200L * notes200 + 100L * notes100
                + 50L * notes50 + 20L * notes20 + 10L * notes10;
        return BigDecimal.valueOf(notesTotal).add(coins).setScale(2, RoundingMode.HALF_UP);
    }

    /** A breakdown that is sent must add up to the amount it was counted for. */
    public static void requireMatches(CashDenominations denominations, BigDecimal amount) {
        if (denominations == null) {
            return;
        }
        BigDecimal expected = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal counted = denominations.total();
        if (counted.compareTo(expected) != 0) {
            throw new BusinessException(ErrorCodes.DENOMINATIONS_TOTAL_MISMATCH,
                    Map.of("denominationTotal", counted.toPlainString(), "amount", expected.toPlainString()),
                    "The notes and coins counted add up to ₹" + counted.toPlainString()
                            + " but the amount entered is ₹" + expected.toPlainString() + ".");
        }
    }
}
