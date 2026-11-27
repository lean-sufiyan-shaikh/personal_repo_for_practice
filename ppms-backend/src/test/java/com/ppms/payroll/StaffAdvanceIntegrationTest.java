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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

        perform(patch(ADVANCES_PATH + "/{id}/void", setup.pumpId(), advance.get("id").asLong()), setup.manager(),
                Map.of("voidReason", "Typed twice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STAFF_ADVANCE_ALREADY_RECOVERED"))
                .andExpect(jsonPath("$.params.recovered").value("800.00"));
    }

    @Test
    void voidedAdvanceGoesBackIntoTheSafeAndIsNotRecovered() throws Exception {
        long advanceId = json(give("1000.00", TODAY).andExpect(status().isCreated())).get("id").asLong();

        JsonNode voided = json(perform(patch(ADVANCES_PATH + "/{id}/void", setup.pumpId(), advanceId), setup.manager(),
                Map.of("voidReason", "Given by mistake")).andExpect(status().isOk()));
        assertThat(voided.get("status").asText()).isEqualTo("VOIDED");
        assertThat(voided.get("outstanding").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(safeBalance()).isEqualByComparingTo("5000.00");
        perform(patch(ADVANCES_PATH + "/{id}/void", setup.pumpId(), advanceId), setup.manager(), Map.of("voidReason", "Again"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STAFF_ADVANCE_ALREADY_VOIDED"));

        JsonNode payroll = generatePayroll(TODAY, TODAY);
        assertThat(payroll.get("advanceRecovered").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(payroll.get("netPay").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(jdbcTemplate.queryForList("SELECT action::text FROM audit_logs WHERE entity_type = 'StaffAdvance'"
                + " AND entity_id = ? ORDER BY id", String.class, Long.toString(advanceId)))
                .containsExactly("STAFF_ADVANCE_GIVEN", "STAFF_ADVANCE_VOIDED");
    }

    @Test
    void advanceCannotBeDatedAheadOrSpendMoreThanTheSafeHolds() throws Exception {
        give("100.00", TODAY.plusDays(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STAFF_ADVANCE_DATE_IN_FUTURE"));
        give("6000.00", TODAY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_OUT_EXCEEDS_BALANCE"));
        perform(post(ADVANCES_PATH, setup.pumpId()), setup.manager(),
                Map.of("staffUserId", createPumpWithStock("0").operator().getId(), "amount", new BigDecimal("10.00"),
                        "advanceDate", TODAY.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listShowsEachStaffMembersOutstandingBalance() throws Exception {
        give("300.00", TODAY).andExpect(status().isCreated());
        give("200.00", TODAY).andExpect(status().isCreated());
        perform(post(ADVANCES_PATH, setup.pumpId()), setup.manager(),
                Map.of("staffUserId", setup.operator().getId(), "amount", new BigDecimal("100.00"),
                        "advanceDate", TODAY.toString(), "note", "Bus fare"))
                .andExpect(status().isCreated());

        JsonNode all = json(perform(get(ADVANCES_PATH, setup.pumpId()), setup.admin()).andExpect(status().isOk()));
        assertThat(all.get("advances")).hasSize(3);
        assertThat(all.get("outstandingByStaff").get(0).get("staffUserId").asLong()).isEqualTo(setup.accountant().getId());
        assertThat(all.get("outstandingByStaff").get(0).get("outstanding").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(all.get("outstandingByStaff").get(1).get("outstanding").decimalValue()).isEqualByComparingTo("100.00");
        perform(get(ADVANCES_PATH, setup.pumpId()), setup.operator()).andExpect(status().isForbidden());
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
