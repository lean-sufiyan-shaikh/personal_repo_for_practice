package com.ppms.settlement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementCoverageTest {

    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);
    private static final List<PendingSettlementRange.Day> THREE_DAYS = List.of(
            day(MAY_1, "1000.00"), day(MAY_1.plusDays(1), "500.00"), day(MAY_1.plusDays(2), "700.00"));

    @Test
    void aSettlementClearingTheOldestDayExactlyIsNotPartialEvenWithLaterDaysPending() {
        SettlementCoverage coverage = SettlementCoverage.of(THREE_DAYS, new BigDecimal("1000.00"));

        assertThat(coverage.fromDate()).isEqualTo(MAY_1);
        assertThat(coverage.toDate()).isEqualTo(MAY_1);
        assertThat(coverage.partial()).isFalse();
    }

    @Test
    void aSettlementStoppingInsideADayIsPartialAndCoversEveryDayItReached() {
        SettlementCoverage coverage = SettlementCoverage.of(THREE_DAYS, new BigDecimal("1200.00"));

        assertThat(coverage.fromDate()).isEqualTo(MAY_1);
        assertThat(coverage.toDate()).isEqualTo(MAY_1.plusDays(1));
        assertThat(coverage.partial()).isTrue();
    }

    @Test
    void aSettlementClearingEverythingIsNotPartial() {
        SettlementCoverage coverage = SettlementCoverage.of(THREE_DAYS, new BigDecimal("2200.00"));

        assertThat(coverage.toDate()).isEqualTo(MAY_1.plusDays(2));
        assertThat(coverage.partial()).isFalse();
    }

    @Test
    void nothingPendingCoversNoDays() {
        SettlementCoverage coverage = SettlementCoverage.of(List.of(), new BigDecimal("10.00"));

        assertThat(coverage.fromDate()).isNull();
        assertThat(coverage.partial()).isFalse();
    }

    private static PendingSettlementRange.Day day(LocalDate date, String amount) {
        return new PendingSettlementRange.Day(date, new BigDecimal(amount));
    }
}
