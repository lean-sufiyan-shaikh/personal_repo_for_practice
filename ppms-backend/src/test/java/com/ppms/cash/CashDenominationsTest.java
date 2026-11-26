package com.ppms.cash;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashDenominationsTest {

    private static final CashDenominations COUNT = new CashDenominations(3, 2, 4, 1, 5, 7, new BigDecimal("12.50"));

    @Test
    void totalAddsEveryNoteAndTheCoins() {
        assertThat(COUNT.total()).isEqualByComparingTo("2532.50");
    }

    @Test
    void breakdownThatAddsUpToTheAmountIsAccepted() {
        assertThatCode(() -> CashDenominations.requireMatches(COUNT, new BigDecimal("2532.5"))).doesNotThrowAnyException();
    }

    @Test
    void missingBreakdownIsAccepted() {
        assertThatCode(() -> CashDenominations.requireMatches(null, new BigDecimal("100"))).doesNotThrowAnyException();
    }

    @Test
    void breakdownThatDiffersFromTheAmountIsRefusedWithBothFigures() {
        assertThatThrownBy(() -> CashDenominations.requireMatches(COUNT, new BigDecimal("2540.00")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.DENOMINATIONS_TOTAL_MISMATCH);
                    assertThat(e.getParams()).containsEntry("denominationTotal", "2532.50").containsEntry("amount", "2540.00");
                });
    }
}
