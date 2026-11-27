package com.ppms.expense;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where an expense was paid from decides the cash it moves once approved: the safe, the petty cash box, or none for
 * the bank and the owner's pocket. Voiding an approved expense reverses that cash; a closed day refuses the void.
 */
class ExpensePaidFromIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPumpWithCashInTheSafe() throws Exception {
        setup = createPumpWithStock("0");
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner(),
                Map.of("eventType", "OPENING_BALANCE", "amount", new BigDecimal("5000.00"), "description", "Safe",
                        "eventDate", TODAY.toString())).andExpect(status().isCreated());
    }

    @Test
    void expensePaidFromTheSafeTakesItsCashOutAndVoidingPutsItBack() throws Exception {
        JsonNode expense = json(record(setup.owner(), "SAFE", "400.00", TODAY).andExpect(status().isCreated()));

        assertThat(expense.get("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(expense.get("paidFrom").asText()).isEqualTo("SAFE");
        long cashEventId = expense.get("cashEventId").asLong();
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM cash_events WHERE id = ? AND event_type = 'CASH_OUT'",
                BigDecimal.class, cashEventId)).isEqualByComparingTo("400.00");
        assertThat(safeBalance()).isEqualByComparingTo("4600.00");

        voidExpense(setup.manager(), expense.get("id").asLong()).andExpect(status().isForbidden());
        JsonNode voided = json(voidExpense(setup.owner(), expense.get("id").asLong()).andExpect(status().isOk()));

        assertThat(voided.get("approvalStatus").asText()).isEqualTo("VOIDED");
        assertThat(voided.get("voidReason").asText()).isEqualTo("Entered twice");
        assertThat(safeBalance()).isEqualByComparingTo("5000.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cash_events WHERE reverses_event_id = ?",
                Long.class, cashEventId)).isEqualTo(1);
        assertThat(auditActions(expense.get("id").asLong())).containsExactly("EXPENSE_RECORDED", "EXPENSE_VOIDED");
        voidExpense(setup.owner(), expense.get("id").asLong())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPENSE_NOT_VOIDABLE"));

        JsonNode report = json(perform(get("/api/pumps/{pumpId}/reports/profit-loss", setup.pumpId())
                .param("from", TODAY.toString()).param("to", TODAY.toString()), setup.owner()).andExpect(status().isOk()));
        assertThat(report.get("approvedExpenses").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void expensePaidFromPettyCashDrawsTheBoxOnlyWhenApprovedAndNeedsEnoughInIt() throws Exception {
        perform(put("/api/pumps/{pumpId}/petty-cash/settings", setup.pumpId()), setup.owner(),
                Map.of("floatAmount", new BigDecimal("1000.00"), "countTolerance", new BigDecimal("10.00")))
                .andExpect(status().isOk());
        perform(post("/api/pumps/{pumpId}/petty-cash/top-ups", setup.pumpId()), setup.manager(),
                Map.of("amount", new BigDecimal("300.00"))).andExpect(status().isCreated());

        JsonNode pending = json(record(setup.manager(), "PETTY_CASH", "250.00", TODAY).andExpect(status().isCreated()));
        assertThat(pending.get("approvalStatus").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(pettyBalance()).isEqualByComparingTo("300.00");

        perform(patch("/api/pumps/{pumpId}/expenses/{id}/approve", setup.pumpId(), pending.get("id").asLong()),
                setup.owner(), Map.of("action", "APPROVED")).andExpect(status().isOk());
        assertThat(pettyBalance()).isEqualByComparingTo("50.00");

        record(setup.owner(), "PETTY_CASH", "60.00", TODAY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PETTY_CASH_BALANCE_TOO_LOW"))
                .andExpect(jsonPath("$.params.balance").value("50.00"));

        voidExpense(setup.admin(), pending.get("id").asLong()).andExpect(status().isOk());
        assertThat(pettyBalance()).isEqualByComparingTo("300.00");
        assertThat(safeBalance()).isEqualByComparingTo("4700.00");
    }

    @Test
    void bankAndOwnersPocketExpensesMoveNoCash() throws Exception {
        record(setup.owner(), "BANK", "700.00", TODAY).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashEventId").doesNotExist());
        record(setup.owner(), "OWNER_POCKET", "200.00", TODAY).andExpect(status().isCreated());

        assertThat(safeBalance()).isEqualByComparingTo("5000.00");
        assertThat(pettyBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void futureDatesMissingSourceAndClosedDaysAreRefused() throws Exception {
        record(setup.owner(), "SAFE", "100.00", TODAY.plusDays(1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXPENSE_DATE_IN_FUTURE"));
        perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("category", "MAINTENANCE", "amount", new BigDecimal("100.00"), "description", "No source",
                        "expenseDate", TODAY.toString()))
                .andExpect(status().isBadRequest());

        LocalDate yesterday = TODAY.minusDays(1);
        long expenseId = json(record(setup.owner(), "BANK", "100.00", yesterday).andExpect(status().isCreated()))
                .get("id").asLong();
        jdbcTemplate.update("""
                INSERT INTO day_closes (pump_id, business_date, status, current_version, closed_by_user_id, closed_at)
                VALUES (?, ?, 'CLOSED', 1, ?, NOW())
                """, setup.pumpId(), yesterday, setup.owner().getId());

        voidExpense(setup.owner(), expenseId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"));
    }

    @Test
    void resendingTheSameExpenseWithItsKeyRecordsItOnce() throws Exception {
        String key = "expense-" + shortId();
        Map<String, Object> body = body("SAFE", "150.00", TODAY);
        long first = json(perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()).header("Idempotency-Key", key),
                setup.owner(), body).andExpect(status().isCreated())).get("id").asLong();
        long again = json(perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()).header("Idempotency-Key", key),
                setup.owner(), body).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))).get("id").asLong();

        assertThat(again).isEqualTo(first);
        assertThat(safeBalance()).isEqualByComparingTo("4850.00");
    }

    private ResultActions record(User user, String paidFrom, String amount, LocalDate date) throws Exception {
        return perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), user, body(paidFrom, amount, date));
    }

    private static Map<String, Object> body(String paidFrom, String amount, LocalDate date) {
        return Map.of("category", "MAINTENANCE", "amount", new BigDecimal(amount), "description", "Nozzle hose",
                "expenseDate", date.toString(), "paidFrom", paidFrom);
    }

    private ResultActions voidExpense(User user, long expenseId) throws Exception {
        return perform(post("/api/pumps/{pumpId}/expenses/{id}/void", setup.pumpId(), expenseId), user,
                Map.of("reason", "Entered twice"));
    }

    private BigDecimal safeBalance() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager())).get("balance").decimalValue();
    }

    private BigDecimal pettyBalance() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/petty-cash", setup.pumpId()), setup.manager())).get("balance").decimalValue();
    }

    private java.util.List<String> auditActions(long expenseId) {
        return jdbcTemplate.queryForList("SELECT action::text FROM audit_logs WHERE pump_id = ? AND entity_type = 'PumpExpense'"
                + " AND entity_id = ? ORDER BY id", String.class, setup.pumpId(), Long.toString(expenseId));
    }
}
