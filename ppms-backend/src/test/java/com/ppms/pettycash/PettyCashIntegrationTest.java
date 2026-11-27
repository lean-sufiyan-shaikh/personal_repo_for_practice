package com.ppms.pettycash;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The petty cash box: its float set by owners and admins, top-ups taken from the safe in one go, counts compared with
 * its ledger (notifying the owner beyond tolerance), its ledger's running balance and the Day Close warning.
 */
class PettyCashIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPumpWithCashInTheSafe() throws Exception {
        setup = createPumpWithStock("0");
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner(),
                Map.of("eventType", "OPENING_BALANCE", "amount", new BigDecimal("2000.00"), "description", "Safe",
                        "eventDate", TODAY.toString())).andExpect(status().isCreated());
    }

    @Test
    void onlyOwnersAndAdminsSetTheFloatAndOperatorsCannotTouchTheBox() throws Exception {
        settings(setup.manager(), "1500.00").andExpect(status().isForbidden());
        settings(setup.admin(), "1500.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.floatAmount").value(1500.00))
                .andExpect(jsonPath("$.suggestedTopUp").value(1500.00));

        perform(get("/api/pumps/{pumpId}/petty-cash", setup.pumpId()), setup.operator()).andExpect(status().isForbidden());
        perform(get("/api/pumps/{pumpId}/petty-cash/ledger", setup.pumpId()), setup.accountant()).andExpect(status().isForbidden());
        topUp("100.00").andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/petty-cash/top-ups", setup.pumpId()), setup.operator(),
                Map.of("amount", new BigDecimal("100.00"))).andExpect(status().isForbidden());
        perform(get("/api/pumps/{pumpId}/petty-cash/ledger", setup.pumpId()), otherPumpOwner()).andExpect(status().isForbidden());
        assertThat(auditCount("PETTY_CASH_SETTINGS_UPDATED")).isEqualTo(1);
    }

    @Test
    void topUpMovesCashFromTheSafeIntoTheBoxTogetherAndCannotOverdrawTheSafe() throws Exception {
        JsonNode entry = json(topUp("800.00").andExpect(status().isCreated()));

        assertThat(entry.get("entryType").asText()).isEqualTo("TOP_UP");
        assertThat(entry.get("balanceAfter").decimalValue()).isEqualByComparingTo("800.00");
        Long safeEventId = jdbcTemplate.queryForObject("SELECT safe_cash_event_id FROM petty_cash_entries WHERE id = ?",
                Long.class, entry.get("id").asLong());
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM cash_events WHERE id = ? AND event_type = 'CASH_OUT'",
                BigDecimal.class, safeEventId)).isEqualByComparingTo("800.00");
        assertThat(json(perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager())).get("balance").decimalValue())
                .isEqualByComparingTo("1200.00");

        topUp("1500.00").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CASH_OUT_EXCEEDS_BALANCE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM petty_cash_entries WHERE pump_id = ?", Long.class,
                setup.pumpId())).isEqualTo(1);
        perform(post("/api/pumps/{pumpId}/petty-cash/top-ups", setup.pumpId()), setup.manager(),
                Map.of("amount", new BigDecimal("10.00"), "entryDate", TODAY.plusDays(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PETTY_CASH_DATE_IN_FUTURE"));
        assertThat(auditCount("PETTY_CASH_TOPPED_UP")).isEqualTo(1);
    }

    @Test
    void ledgerShowsTheBalanceAfterEveryMovementNewestFirst() throws Exception {
        topUp("500.00").andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("category", "OTHER", "amount", new BigDecimal("120.00"), "description", "Tea and snacks",
                        "expenseDate", TODAY.toString(), "paidFrom", "PETTY_CASH")).andExpect(status().isCreated());
        topUp("120.00").andExpect(status().isCreated());

        JsonNode ledger = json(perform(get("/api/pumps/{pumpId}/petty-cash/ledger", setup.pumpId()), setup.manager())
                .andExpect(status().isOk())).get("content");
        assertThat(ledger).hasSize(3);
        assertThat(ledger.get(0).get("balanceAfter").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(ledger.get(1).get("entryType").asText()).isEqualTo("EXPENSE");
        assertThat(ledger.get(1).get("amount").decimalValue()).isEqualByComparingTo("-120.00");
        assertThat(ledger.get(1).get("balanceAfter").decimalValue()).isEqualByComparingTo("380.00");
        assertThat(ledger.get(2).get("balanceAfter").decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void countBeyondToleranceNotifiesTheOwnerAndAMatchingCountDoesNot() throws Exception {
        settings(setup.owner(), "500.00").andExpect(status().isOk());
        topUp("500.00").andExpect(status().isCreated());

        JsonNode matching = json(count("495.00").andExpect(status().isCreated()));
        assertThat(matching.get("varianceAmount").decimalValue()).isEqualByComparingTo("-5.00");
        assertThat(matching.get("beyondTolerance").asBoolean()).isFalse();
        assertThat(varianceNotifications()).isZero();

        JsonNode shortCount = json(count("420.00").andExpect(status().isCreated()));
        assertThat(shortCount.get("expectedAmount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(shortCount.get("beyondTolerance").asBoolean()).isTrue();
        assertThat(varianceNotifications()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT params->>'varianceTypeCode' FROM notifications WHERE pump_id = ?"
                + " AND type = 'PETTY_CASH_COUNT_VARIANCE'", String.class, setup.pumpId())).isEqualTo("SHORT");
        assertThat(auditCount("PETTY_CASH_COUNTED")).isEqualTo(2);
    }

    @Test
    void dayCloseWarnsWhileTheBoxInUseWasNotCountedThatDay() throws Exception {
        assertThat(pettyWarnings()).isZero();
        settings(setup.owner(), "500.00").andExpect(status().isOk());
        assertThat(pettyWarnings()).isEqualTo(1);

        count("0.00").andExpect(status().isCreated());
        assertThat(pettyWarnings()).isZero();
    }

    private ResultActions settings(User user, String floatAmount) throws Exception {
        return perform(put("/api/pumps/{pumpId}/petty-cash/settings", setup.pumpId()), user,
                Map.of("floatAmount", new BigDecimal(floatAmount), "countTolerance", new BigDecimal("10.00")));
    }

    private ResultActions topUp(String amount) throws Exception {
        return perform(post("/api/pumps/{pumpId}/petty-cash/top-ups", setup.pumpId()), setup.manager(),
                Map.of("amount", new BigDecimal(amount), "notes", "Weekly refill"));
    }

    private ResultActions count(String amount) throws Exception {
        return perform(post("/api/pumps/{pumpId}/petty-cash/counts", setup.pumpId()), setup.manager(),
                Map.of("countedAmount", new BigDecimal(amount)));
    }

    private long pettyWarnings() throws Exception {
        JsonNode checks = json(perform(get("/api/pumps/{pumpId}/day-closes/preview", setup.pumpId())
                .param("date", TODAY.toString()), setup.manager()).andExpect(status().isOk())).get("checks");
        long warnings = 0;
        for (JsonNode check : checks) {
            if ("PETTY_CASH_NOT_COUNTED".equals(check.get("code").asText())) {
                assertThat(check.get("blocking").asBoolean()).isFalse();
                warnings++;
            }
        }
        return warnings;
    }

    private long varianceNotifications() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications WHERE pump_id = ?"
                + " AND type = 'PETTY_CASH_COUNT_VARIANCE'", Long.class, setup.pumpId());
    }

    private long auditCount(String action) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action::text = ?",
                Long.class, setup.pumpId(), action);
    }

    private User otherPumpOwner() throws Exception {
        return createPumpWithStock("0").owner();
    }
}
