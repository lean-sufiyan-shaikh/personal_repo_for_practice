package com.ppms.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The collections are counted and locked before the expected amount is shown, and the close runs on that count. */
class BlindCashCountIntegrationTest extends ApiIntegrationTest {

    private static final String COUNT_PATH = "/api/pumps/{pumpId}/shifts/{id}/blind-count";
    private static final String CLOSE_PATH = "/api/pumps/{pumpId}/shifts/{id}/close";

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPumpThatCountsBlind() throws Exception {
        setup = createPumpWithStock("5000");
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(), Map.of("blindCashCount", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blindCashCount").value(true));
    }

    @Test
    void closeNeedsTheCountFirstAndTheShortageIsWorkedOutOnTheCountedFigures() throws Exception {
        long shiftId = openShift();

        perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), closeRequest("1010.000", "900.00", "Short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BLIND_COUNT_REQUIRED"));

        JsonNode counted = json(perform(post(COUNT_PATH, setup.pumpId(), shiftId), setup.operator(), count("900.00"))
                .andExpect(status().isCreated()));
        assertThat(counted.get("blindCountRequired").asBoolean()).isTrue();
        assertThat(counted.get("blindCount").get("cashCollected").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(counted.get("blindCount").get("countedByName").asText()).isEqualTo(setup.operator().getFullName());
        assertThat(counted.get("totalAmountDue").isNull()).isTrue();

        perform(post(COUNT_PATH, setup.pumpId(), shiftId), setup.manager(), count("1000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BLIND_COUNT_ALREADY_RECORDED"));
        perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), closeRequest("1010.000", "1000.00", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BLIND_COUNT_CHANGED_AT_CLOSE"))
                .andExpect(jsonPath("$.params.cashCounted").value("900.00"));

        JsonNode closed = json(perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1010.000", "900.00", "Short by a note")).andExpect(status().isOk()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED_DISCREPANCY_PENDING");
        assertThat(closed.get("totalAmountDue").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(closed.get("discrepancyType").asText()).isEqualTo("SHORT");
        assertThat(closed.get("discrepancyAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(auditCount(shiftId, "BLIND_CASH_COUNTED")).isEqualTo(1);
    }

    @Test
    void escalationThresholdAndRoundingToleranceApplyToTheBlindCount() throws Exception {
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("discrepancyEscalationThreshold", new BigDecimal("50.00"), "roundingTolerance", new BigDecimal("1.00")))
                .andExpect(status().isOk());

        long escalated = openShift();
        perform(post(COUNT_PATH, setup.pumpId(), escalated), setup.manager(), count("900.00")).andExpect(status().isCreated());
        JsonNode closedShort = json(perform(post(CLOSE_PATH, setup.pumpId(), escalated), setup.manager(),
                closeRequest("1010.000", "900.00", "Short")).andExpect(status().isOk()));
        assertThat(closedShort.get("status").asText()).isEqualTo("CLOSED_DISCREPANCY_PENDING_APPROVAL");

        long rounded = openShift();
        perform(post(COUNT_PATH, setup.pumpId(), rounded), setup.manager(), count("999.50")).andExpect(status().isCreated());
        JsonNode closedRounded = json(perform(post(CLOSE_PATH, setup.pumpId(), rounded), setup.manager(),
                closeRequest("1020.000", "999.50", null)).andExpect(status().isOk()));
        assertThat(closedRounded.get("status").asText()).isEqualTo("CLOSED_BALANCED");
        assertThat(closedRounded.get("roundingAdjustment").decimalValue()).isEqualByComparingTo("-0.50");
    }

    @Test
    void anOperatorCountsOnlyAShiftTheyWork() throws Exception {
        long shiftId = openShift();
        User otherOperator = saveUser(UserRole.OPERATOR, setup.pumpId());

        perform(post(COUNT_PATH, setup.pumpId(), shiftId), otherOperator, count("100.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BLIND_COUNT_NOT_OWN_SHIFT"));
        perform(post(COUNT_PATH, setup.pumpId(), shiftId), setup.accountant(), count("100.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void countMustMatchItsNotesAndAClosedShiftCannotBeCounted() throws Exception {
        long shiftId = openShift();
        Map<String, Object> wrongNotes = new HashMap<>(count("500.00"));
        wrongNotes.put("cashDenominations", Map.of("notes500", 2, "notes200", 0, "notes100", 0, "notes50", 0,
                "notes20", 0, "notes10", 0, "coins", BigDecimal.ZERO));
        perform(post(COUNT_PATH, setup.pumpId(), shiftId), setup.operator(), wrongNotes)
                .andExpect(status().isBadRequest());

        perform(post(COUNT_PATH, setup.pumpId(), shiftId), setup.operator(), count("1000.00")).andExpect(status().isCreated());
        perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), closeRequest("1010.000", "1000.00", null))
                .andExpect(status().isOk());
        perform(post(COUNT_PATH, setup.pumpId(), shiftId), setup.manager(), count("1000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIFT_NOT_OPEN"));
    }

    @Test
    void pumpThatTurnedBlindCountOffClosesWithoutACount() throws Exception {
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(), Map.of("blindCashCount", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blindCashCount").value(false));
        long shiftId = openShift();

        JsonNode shift = json(perform(get("/api/pumps/{pumpId}/shifts/{id}", setup.pumpId(), shiftId), setup.operator())
                .andExpect(status().isOk()));
        assertThat(shift.get("blindCountRequired").asBoolean()).isFalse();
        perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), closeRequest("1010.000", "1000.00", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));
    }

    private long openShift() throws Exception {
        JsonNode opened = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().isCreated()));
        return opened.get("id").asLong();
    }

    private static Map<String, Object> count(String cash) {
        return Map.of("cashCollected", new BigDecimal(cash), "upiCollected", BigDecimal.ZERO,
                "cardCollected", BigDecimal.ZERO, "fleetCardCollected", BigDecimal.ZERO);
    }

    private Map<String, Object> closeRequest(String endReading, String cash, String reason) {
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal(endReading))));
        request.put("cashCollected", new BigDecimal(cash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        request.put("discrepancyReason", reason);
        return request;
    }

    private int auditCount(long shiftId, String action) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action::text = ?",
                Integer.class, String.valueOf(shiftId), action);
    }
}
