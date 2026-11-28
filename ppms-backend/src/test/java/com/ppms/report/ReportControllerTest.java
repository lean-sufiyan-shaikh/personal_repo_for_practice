package com.ppms.report;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReportControllerTest {

    private static final Long PUMP_ID = 3L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    private final ReportController controller = new ReportController(mock(ReportService.class),
            mock(ProfitLossReportService.class), mock(InventoryReportService.class), mock(BalanceSheetService.class));

    @Test
    void profitAndLossCoversUpToAYear() {
        assertThatCode(() -> controller.getProfitLoss(PUMP_ID, TODAY.minusDays(365), TODAY)).doesNotThrowAnyException();
        assertTooLong(() -> controller.getProfitLoss(PUMP_ID, TODAY.minusDays(366), TODAY), 366);
    }

    @Test
    void reportsListingEveryShiftOrEntryCoverUpToAQuarter() {
        LocalDate quarterAgo = TODAY.minusDays(92);
        List<ThrowingCallable> detailReports = List.of(
                () -> controller.getShiftsReport(PUMP_ID, quarterAgo, TODAY),
                () -> controller.getOperatorDiscrepancy(PUMP_ID, quarterAgo, TODAY),
                () -> controller.getOperatorDuty(PUMP_ID, 9L, quarterAgo, TODAY),
                () -> controller.getDipPl(PUMP_ID, quarterAgo, TODAY),
                () -> controller.getExpensesReport(PUMP_ID, quarterAgo, TODAY));

        detailReports.forEach(report -> assertTooLong(report, 92));
        assertThatCode(() -> controller.getShiftsReport(PUMP_ID, TODAY.minusDays(91), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void rangeEndingBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> controller.getProfitLoss(PUMP_ID, TODAY, TODAY.minusDays(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.DATE_RANGE_START_AFTER_END));
    }

    private static void assertTooLong(ThrowingCallable report, int maxDays) {
        assertThatThrownBy(report).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(ErrorCodes.DATE_RANGE_TOO_LONG);
            assertThat(error.getParams()).containsEntry("maxDays", maxDays);
        });
    }
}
