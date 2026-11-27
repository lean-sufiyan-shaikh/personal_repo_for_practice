package com.ppms.monthclose;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Last month closes only once every day of it is approved or listed as an exception; its summary is stored as a
 * version, its days refuse changes and re-opens until the owner re-opens it, and a later close adds the next version.
 */
class MonthCloseIntegrationTest extends ApiIntegrationTest {

    private static final YearMonth LAST_MONTH = YearMonth.from(TestDates.today()).minusMonths(1);
    private static final LocalDate UNCLOSED_DAY = LAST_MONTH.atDay(10);
    private static final LocalDate UNAPPROVED_DAY = LAST_MONTH.atDay(11);

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPumpSetUpBeforeLastMonthWithItsMoneyMoved() throws Exception {
        setup = createPumpWithStock("0");
        jdbcTemplate.update("UPDATE pump_locations SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LAST_MONTH.minusMonths(1).atDay(1).atStartOfDay()), setup.pumpId());
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner(),
                Map.of("eventType", "OPENING_BALANCE", "amount", new BigDecimal("1000.00"), "description", "Safe",
                        "eventDate", LAST_MONTH.atDay(2).toString())).andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/petty-cash/top-ups", setup.pumpId()), setup.manager(),
                Map.of("amount", new BigDecimal("200.00"), "entryDate", LAST_MONTH.atDay(5).toString()))
                .andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("category", "UTILITIES", "amount", new BigDecimal("300.00"), "description", "Electricity",
                        "expenseDate", LAST_MONTH.atDay(3).toString(), "paidFrom", "BANK")).andExpect(status().isCreated());
        for (LocalDate day : LAST_MONTH.atDay(1).datesUntil(LAST_MONTH.atEndOfMonth().plusDays(1)).toList()) {
            if (!day.equals(UNCLOSED_DAY)) {
                jdbcTemplate.update("""
                        INSERT INTO day_closes (pump_id, business_date, status, current_version, closed_by_user_id,
                                                closed_at, approved_by_user_id, approved_at)
                        VALUES (?, ?, ?, 1, ?, NOW(), ?, ?)
                        """, setup.pumpId(), day, day.equals(UNAPPROVED_DAY) ? "CLOSED" : "APPROVED", setup.owner().getId(),
                        day.equals(UNAPPROVED_DAY) ? null : setup.owner().getId(),
                        day.equals(UNAPPROVED_DAY) ? null : java.sql.Timestamp.valueOf(day.atTime(23, 0)));
            }
        }
    }

    @Test
    void monthClosesOnlyWithEveryDayApprovedOrExcusedAndStoresItsSummary() throws Exception {
        JsonNode before = json(perform(get("/api/pumps/{pumpId}/month-closes/{month}", setup.pumpId(), LAST_MONTH),
                setup.admin()).andExpect(status().isOk()));
        assertThat(before.get("days")).hasSize(LAST_MONTH.lengthOfMonth());
        assertThat(before.get("notReadyDayCount").asInt()).isEqualTo(2);
        assertThat(before.get("status").isNull()).isTrue();

        close(setup.admin(), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_DAYS_NOT_READY"))
                .andExpect(jsonPath("$.params.count").value(2));
        close(setup.admin(), List.of(Map.of("date", TestDates.today().toString(), "reason", "Not this month")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_EXCEPTION_NOT_IN_MONTH"));

        JsonNode closed = json(close(setup.admin(), exceptions()).andExpect(status().isOk()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("currentVersion").asInt()).isEqualTo(1);
        JsonNode summary = closed.get("summary");
        assertThat(summary.get("month").asText()).isEqualTo(LAST_MONTH.toString());
        assertThat(summary.get("businessDayCount").asInt()).isEqualTo(LAST_MONTH.lengthOfMonth());
        assertThat(summary.get("approvedDayCount").asInt()).isEqualTo(LAST_MONTH.lengthOfMonth() - 2);
        assertThat(summary.get("exceptions").findValuesAsText("statusCode")).containsExactly("NOT_CLOSED", "CLOSED");
        assertThat(summary.get("costs").get("expenses").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(summary.get("costs").get("total").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(summary.get("netProfit").decimalValue()).isEqualByComparingTo(
                summary.get("grossProfit").decimalValue().subtract(new BigDecimal("300.00")));
        assertThat(summary.get("safeClosing").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(summary.get("pettyCashClosing").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(summary.get("tanks")).hasSize(1);
        assertThat(summary.get("payments").get("total").decimalValue()).isEqualByComparingTo("0.00");

        close(setup.owner(), exceptions())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_ALREADY_CLOSED"));
    }

    @Test
    void closedMonthRefusesDayReopensAndWritesOnItsDaysUntilTheOwnerReopensIt() throws Exception {
        close(setup.owner(), exceptions()).andExpect(status().isOk());

        perform(post("/api/pumps/{pumpId}/day-closes/{date}/reopen", setup.pumpId(), LAST_MONTH.atDay(1)), setup.owner(),
                Map.of("reason", "Fix a reading"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_CLOSED"))
                .andExpect(jsonPath("$.params.month").value(LAST_MONTH.toString()));
        perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("category", "OTHER", "amount", new BigDecimal("50.00"), "description", "Late bill",
                        "expenseDate", UNCLOSED_DAY.toString(), "paidFrom", "BANK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_CLOSED"));

        reopen(setup.admin()).andExpect(status().isForbidden());
        JsonNode reopened = json(reopen(setup.owner()).andExpect(status().isOk()));
        assertThat(reopened.get("status").asText()).isEqualTo("REOPENED");
        assertThat(reopened.get("versions").get(0).get("reopenReason").asText()).isEqualTo("Supplier bill arrived late");

        perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("category", "OTHER", "amount", new BigDecimal("50.00"), "description", "Late bill",
                        "expenseDate", UNCLOSED_DAY.toString(), "paidFrom", "BANK"))
                .andExpect(status().isCreated());
    }

    @Test
    void closingAgainAfterAReopenAddsTheNextVersionAndKeepsTheFirst() throws Exception {
        close(setup.owner(), exceptions()).andExpect(status().isOk());
        reopen(setup.owner()).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE day_closes SET status = 'APPROVED', approved_by_user_id = closed_by_user_id,"
                + " approved_at = NOW() WHERE pump_id = ? AND business_date = ?", setup.pumpId(), UNAPPROVED_DAY);

        JsonNode second = json(close(setup.owner(),
                List.of(Map.of("date", UNCLOSED_DAY.toString(), "reason", "Pump shut for repairs")))
                .andExpect(status().isOk()));

        assertThat(second.get("currentVersion").asInt()).isEqualTo(2);
        assertThat(second.get("versions")).hasSize(2);
        assertThat(second.get("summary").get("exceptions")).hasSize(1);
        JsonNode first = json(perform(get("/api/pumps/{pumpId}/month-closes/{month}/versions/1", setup.pumpId(), LAST_MONTH),
                setup.owner()).andExpect(status().isOk()));
        assertThat(first.get("exceptions")).hasSize(2);
        assertThat(jdbcTemplate.queryForList("SELECT action::text FROM audit_logs WHERE pump_id = ? AND entity_type = 'MonthClose'"
                + " ORDER BY id", String.class, setup.pumpId())).containsExactly("MONTH_CLOSED", "MONTH_REOPENED", "MONTH_CLOSED");
        perform(get("/api/pumps/{pumpId}/month-closes", setup.pumpId()), setup.owner()).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currentVersion").value(2));
    }

    @Test
    void currentMonthCannotBeClosedAndManagersCannotSeeMonthCloses() throws Exception {
        perform(post("/api/pumps/{pumpId}/month-closes/{month}/close", setup.pumpId(), YearMonth.from(TestDates.today())),
                setup.owner(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MONTH_NOT_OVER"));
        perform(get("/api/pumps/{pumpId}/month-closes/{month}", setup.pumpId(), LAST_MONTH), setup.manager())
                .andExpect(status().isForbidden());
        close(setup.manager(), exceptions()).andExpect(status().isForbidden());
        perform(get("/api/pumps/{pumpId}/month-closes", setup.pumpId()), createPumpWithStock("0").owner())
                .andExpect(status().isForbidden());
    }

    private static List<Map<String, Object>> exceptions() {
        return List.of(
                Map.of("date", UNCLOSED_DAY.toString(), "reason", "Pump shut for repairs"),
                Map.of("date", UNAPPROVED_DAY.toString(), "reason", "Owner travelling, approves later"));
    }

    private ResultActions close(User user, List<Map<String, Object>> exceptions) throws Exception {
        return perform(post("/api/pumps/{pumpId}/month-closes/{month}/close", setup.pumpId(), LAST_MONTH), user,
                Map.of("exceptions", exceptions));
    }

    private ResultActions reopen(User user) throws Exception {
        return perform(post("/api/pumps/{pumpId}/month-closes/{month}/reopen", setup.pumpId(), LAST_MONTH), user,
                Map.of("reason", "Supplier bill arrived late"));
    }
}
