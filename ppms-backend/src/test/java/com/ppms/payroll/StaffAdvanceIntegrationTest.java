package com.ppms.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A cash advance leaves the safe when given and is taken back from later payroll, in part or whole, never below zero. */
class StaffAdvanceIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();
    private static final String ADVANCES_PATH = "/api/pumps/{pumpId}/staff-advances";

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPumpWithCashInTheSafe() throws Exception {
        setup = createPumpWithStock("0");
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner(),
                Map.of("eventType", "OPENING_BALANCE", "amount", new BigDecimal("5000.00"), "description", "Float",
                        "eventDate", TODAY.toString()))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void advanceIsTakenOutOfTheSafeAndRecoveredAcrossTwoPayrollRuns() throws Exception {
        JsonNode advance = json(give("1500.00", TODAY).andExpect(status().isCreated()));
        assertThat(advance.get("outstanding").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(safeBalance()).isEqualByComparingTo("3500.00");

        JsonNode first = generatePayroll(TODAY.minusDays(1), TODAY.minusDays(1));
        assertThat(first.get("grossAmount").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(first.get("advanceRecovered").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(first.get("netPay").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(outstandingOfAccountant()).isEqualByComparingTo("700.00");

        JsonNode second = generatePayroll(TODAY, TODAY);
        assertThat(second.get("advanceRecovered").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(second.get("netPay").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(outstandingOfAccountant()).isEqualByComparingTo("0.00");

        perform(delete("/api/pumps/{pumpId}/payroll/{id}", setup.pumpId(), second.get("id").asLong()), setup.owner())
                .andExpect(status().isNoContent());
        assertThat(outstandingOfAccountant()).isEqualByComparingTo("700.00");
    }

    private ResultActions give(String amount, LocalDate date) throws Exception {
        return perform(post(ADVANCES_PATH, setup.pumpId()), setup.manager(),
                Map.of("staffUserId", setup.accountant().getId(), "amount", new BigDecimal(amount),
                        "advanceDate", date.toString()));
    }

    private JsonNode generatePayroll(LocalDate from, LocalDate to) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/payroll/generate", setup.pumpId()), setup.owner(),
                Map.of("userId", setup.accountant().getId(), "periodFrom", from.toString(), "periodTo", to.toString()))
                .andExpect(status().isCreated()));
    }

    private BigDecimal outstandingOfAccountant() throws Exception {
        JsonNode list = json(perform(get(ADVANCES_PATH, setup.pumpId()).param("userId", setup.accountant().getId().toString()),
                setup.manager()).andExpect(status().isOk()));
        return list.get("outstandingByStaff").get(0).get("outstanding").decimalValue();
    }

    private BigDecimal safeBalance() {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN event_type::text IN ('OPENING_BALANCE', 'CASH_IN', 'CASH_DROP') THEN amount
                                         WHEN event_type::text IN ('CASH_OUT', 'BANK_DEPOSIT') THEN -amount ELSE 0 END), 0)
                FROM cash_events WHERE pump_id = ?
                """, BigDecimal.class, setup.pumpId());
    }
}
