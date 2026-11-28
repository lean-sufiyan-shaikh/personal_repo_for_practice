package com.ppms.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.report.BalanceSheetDetailResponse;
import com.ppms.report.BalanceSheetSupportService;
import com.ppms.report.ReportPeriod;
import com.ppms.shift.DiscrepancyResolution;
import com.ppms.shift.DiscrepancyType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Salary deductions chosen after a payroll, deductions larger than the pay, and paying a payroll: its date, mode and
 * reference, the cash it takes out of the safe, and where it shows as a cost. The operator earns ₹80 an hour.
 */
class PayrollAccuracyIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private PayrollService payrollService;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private BalanceSheetSupportService balanceSheetSupportService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private User operator;

    @BeforeEach
    void createPumpWithPaidOperator() throws Exception {
        setup = createPumpWithStock("0");
        operator = setup.operator();
        operator.setShift1HourlyRate(new BigDecimal("100.00"));
        operator.setStandardHourlyRate(new BigDecimal("80.00"));
        operator = userRepository.save(operator);
    }

    @Test
    void salaryDeductionChosenAfterItsPeriodsPayrollIsTakenByTheNextPayroll() {
        Shift lateDeduction = saveShift(TODAY.minusDays(20), ShiftStatus.CLOSED_DISCREPANCY_PENDING, "120.00");
        PayrollRecord first = payrollService.generate(setup.pumpId(), request(TODAY.minusDays(30), TODAY.minusDays(15)),
                setup.owner());
        assertThat(first.getDeductions()).isEqualByComparingTo("0.00");

        lateDeduction.setStatus(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        lateDeduction.setDiscrepancyResolution(DiscrepancyResolution.SALARY_DEDUCTION);
        lateDeduction.setDiscrepancyResolvedAt(OffsetDateTime.now());
        shiftRepository.save(lateDeduction);
        saveShift(TODAY.minusDays(3), ShiftStatus.CLOSED_BALANCED, null);

        PayrollRecord next = payrollService.generate(setup.pumpId(), request(TODAY.minusDays(14), TODAY), setup.owner());
        PayrollRecord after = payrollService.generate(setup.pumpId(), request(TODAY.plusDays(1), TODAY.plusDays(10)),
                setup.owner());

        assertThat(next.getDeductions()).isEqualByComparingTo("120.00");
        assertThat(next.getNetPay()).isEqualByComparingTo("520.00");
        assertThat(after.getDeductions()).isEqualByComparingTo("0.00");
    }

    @Test
    void deductionsAboveThePayAreCarriedIntoTheNextPayrollAndHoldTheEarlierDraft() {
        Shift shortShift = saveShift(TODAY.minusDays(20), ShiftStatus.CLOSED_DISCREPANCY_PENDING, "1340.00");
        GeneratePayrollRequest firstRequest = request(TODAY.minusDays(30), TODAY.minusDays(15));
        firstRequest.setDeductFromSalaryShiftIds(List.of(shortShift.getId()));
        PayrollRecord first = payrollService.generate(setup.pumpId(), firstRequest, setup.owner());
        assertThat(first.getUncoveredDeductions()).isEqualByComparingTo("700.00");

        saveShift(TODAY.minusDays(3), ShiftStatus.CLOSED_BALANCED, null);
        saveShift(TODAY.minusDays(2), ShiftStatus.CLOSED_BALANCED, null);
        PayrollRecord next = payrollService.generate(setup.pumpId(), request(TODAY.minusDays(14), TODAY), setup.owner());

        assertThat(next.getGrossAmount()).isEqualByComparingTo("1280.00");
        assertThat(next.getOpeningDeductions()).isEqualByComparingTo("700.00");
        assertThat(next.getDeductions()).isEqualByComparingTo("700.00");
        assertThat(next.getNetPay()).isEqualByComparingTo("580.00");
        assertThat(next.getUncoveredDeductions()).isEqualByComparingTo("0.00");
        assertThat(payrollRepository.findById(first.getId()).orElseThrow().getCarriedToPayrollId()).isEqualTo(next.getId());

        assertThatThrownBy(() -> payrollService.deleteDraft(setup.pumpId(), first.getId(), setup.owner()))
                .hasMessageContaining("carried into payroll #" + next.getId());
        payrollService.deleteDraft(setup.pumpId(), next.getId(), setup.owner());
        assertThat(payrollRepository.findById(first.getId()).orElseThrow().getCarriedToPayrollId()).isNull();
        payrollService.deleteDraft(setup.pumpId(), first.getId(), setup.owner());
        assertThat(shiftRepository.findById(shortShift.getId()).orElseThrow().getStatus())
                .isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
    }

    @Test
    void payingInCashRecordsThePaymentTakesItFromTheSafeAndCostsItInTheReports() throws Exception {
        saveShift(TODAY.minusDays(1), ShiftStatus.CLOSED_BALANCED, null);
        long recordId = generateAndApprove();
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner(),
                Map.of("eventType", "OPENING_BALANCE", "amount", new BigDecimal("5000.00"), "description", "Safe",
                        "eventDate", TODAY.toString())).andExpect(status().is2xxSuccessful());

        JsonNode paid = json(markPaid(recordId, paidOn(TODAY, "CASH")).andExpect(status().isOk()));

        assertThat(paid.get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("paidDate").asText()).isEqualTo(TODAY.toString());
        assertThat(paid.get("paymentMode").asText()).isEqualTo("CASH");
        assertThat(paid.get("paymentReference").asText()).isEqualTo("Voucher 12");
        assertThat(paid.get("paidBy").asLong()).isEqualTo(setup.owner().getId());
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM cash_events WHERE id = ? AND event_type = 'CASH_OUT'",
                BigDecimal.class, paid.get("cashEventId").asLong())).isEqualByComparingTo("640.00");
        assertThat(jdbcTemplate.queryForList("SELECT action::text FROM audit_logs WHERE pump_id = ? AND entity_id = ?"
                + " AND entity_type = 'PayrollRecord' ORDER BY id", String.class, setup.pumpId(), Long.toString(recordId)))
                .containsExactly("PAYROLL_APPROVED", "PAYROLL_PAID");

        JsonNode report = json(perform(get("/api/pumps/{pumpId}/reports/profit-loss", setup.pumpId())
                .param("from", TODAY.toString()).param("to", TODAY.toString()), setup.manager()).andExpect(status().isOk()));
        assertThat(report.get("payrollPaid").decimalValue()).isEqualByComparingTo("640.00");
        assertThat(report.get("netOperatingProfit").decimalValue()).isEqualByComparingTo(
                report.get("grossProfit").decimalValue().subtract(new BigDecimal("640.00")));

        BalanceSheetDetailResponse.ExpenseSummary daySheetExpenses = balanceSheetSupportService.buildExpenseSummary(
                setup.pumpId(), new ReportPeriod(TODAY, TestDates.now(), TestDates.now(), false));
        assertThat(daySheetExpenses.getTotalAmount()).isEqualByComparingTo("640.00");
        assertThat(daySheetExpenses.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getCategory()).isEqualTo("PAYROLL");
            assertThat(line.getDescription()).startsWith(operator.getFullName());
            assertThat(line.getRecordedByName()).isEqualTo(setup.owner().getFullName());
        });
    }

    private long generateAndApprove() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", operator.getId());
        body.put("periodFrom", TODAY.minusDays(6).toString());
        body.put("periodTo", TODAY.toString());
        long recordId = json(perform(post("/api/pumps/{pumpId}/payroll/generate", setup.pumpId()), setup.owner(), body)
                .andExpect(status().isCreated())).get("id").asLong();
        markPaid(recordId, Map.of("status", "APPROVED")).andExpect(status().isOk());
        return recordId;
    }

    private ResultActions markPaid(long recordId, Map<String, Object> body) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/payroll/{id}/status", setup.pumpId(), recordId), setup.owner(), body);
    }

    private static Map<String, Object> paidOn(LocalDate date, String mode) {
        return Map.of("status", "PAID", "paidDate", date.toString(), "paymentMode", mode,
                "paymentReference", "Voucher 12");
    }

    private GeneratePayrollRequest request(LocalDate from, LocalDate to) {
        GeneratePayrollRequest request = new GeneratePayrollRequest();
        request.setUserId(operator.getId());
        request.setPeriodFrom(from);
        request.setPeriodTo(to);
        return request;
    }

    /** An eight-hour day shift of the operator, short by {@code shortAmount} when given. */
    private Shift saveShift(LocalDate shiftDate, ShiftStatus status, String shortAmount) {
        OffsetDateTime start = shiftDate.atTime(9, 0).atOffset(TestDates.now().getOffset());
        return shiftRepository.save(Shift.builder()
                .pumpId(setup.pumpId())
                .duId(setup.du().getId())
                .operatorId(operator.getId())
                .openedByUserId(setup.manager().getId())
                .shiftDate(shiftDate)
                .actualStartTime(start)
                .actualEndTime(start.plusHours(8))
                .status(status)
                .discrepancyAmount(shortAmount == null ? null : new BigDecimal(shortAmount))
                .discrepancyType(shortAmount == null ? null : DiscrepancyType.SHORT)
                .build());
    }
}
