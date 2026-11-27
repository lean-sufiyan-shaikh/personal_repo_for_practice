package com.ppms.bank;

import com.ppms.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankStatementDatesTest {

    @Test
    void autoReadsDayFirstWhenAnyFirstPartIsAboveTwelve() {
        assertThat(BankStatementDates.resolve(List.of("04/05/2026", "25/05/2026"), BankStatementDateFormat.AUTO))
                .isEqualTo(BankStatementDateFormat.DAY_FIRST);
    }

    @Test
    void autoReadsMonthFirstWhenAnySecondPartIsAboveTwelve() {
        assertThat(BankStatementDates.resolve(List.of("04/05/2026", "05/25/2026"), BankStatementDateFormat.AUTO))
                .isEqualTo(BankStatementDateFormat.MONTH_FIRST);
    }

    @Test
    void autoFallsBackToDayFirstWhenNothingTellsThemApart() {
        assertThat(BankStatementDates.resolve(List.of("04/05/2026", "2026-05-06"), BankStatementDateFormat.AUTO))
                .isEqualTo(BankStatementDateFormat.DAY_FIRST);
    }

    @Test
    void autoRefusesAFileMixingBothOrders() {
        assertThatThrownBy(() -> BankStatementDates.resolve(List.of("25/05/2026", "05/25/2026"), BankStatementDateFormat.AUTO))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aChosenFormatIsUsedAsIs() {
        assertThat(BankStatementDates.resolve(List.of("25/05/2026"), BankStatementDateFormat.MONTH_FIRST))
                .isEqualTo(BankStatementDateFormat.MONTH_FIRST);
    }

    @Test
    void parsesEachLayout() {
        assertThat(BankStatementDates.parse("04/05/2026", BankStatementDateFormat.DAY_FIRST)).isEqualTo(LocalDate.of(2026, 5, 4));
        assertThat(BankStatementDates.parse("04-05-2026", BankStatementDateFormat.MONTH_FIRST)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(BankStatementDates.parse("2026-05-04", BankStatementDateFormat.MONTH_FIRST)).isEqualTo(LocalDate.of(2026, 5, 4));
    }

    @Test
    void anImpossibleOrUnreadableDateIsNull() {
        assertThat(BankStatementDates.parse("25/05/2026", BankStatementDateFormat.MONTH_FIRST)).isNull();
        assertThat(BankStatementDates.parse("yesterday", BankStatementDateFormat.DAY_FIRST)).isNull();
    }
}
