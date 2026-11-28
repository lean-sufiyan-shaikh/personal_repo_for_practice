package com.ppms.adjustment;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A forward meter adjustment is fuel that left without a sale: valued, drawn from the tank, and signed off by an Owner/Admin. */
class MeterAdjustmentIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("5000");
        setReading("1000.000");
    }

    @Test
    void managersForwardAdjustmentWaitsForApprovalAndThenDrawsTheUnbilledLitresFromTheTank() throws Exception {
        JsonNode requested = json(adjust(setup.manager(), "1050.000", "Meter ran during repair")
                .andExpect(status().isCreated()));

        assertThat(requested.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(requested.get("unbilledLitres").decimalValue()).isEqualByComparingTo("50.000");
        assertThat(requested.get("unbilledValue").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(lastReading()).isEqualByComparingTo("1000.000");
        assertThat(tankStock()).isEqualByComparingTo("5000.000");

        JsonNode queued = pendingApproval(requested.get("id").asLong());
        assertThat(queued.get("litres").decimalValue()).isEqualByComparingTo("50.000");
        assertThat(queued.get("amount").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(queued.get("nozzleLabel").asText()).contains("Nozzle");

        decide(requested.get("id").asLong(), "APPROVED", null)
                .andExpect(jsonPath("$.succeeded").value(1));

        assertThat(lastReading()).isEqualByComparingTo("1050.000");
        assertThat(tankStock()).isEqualByComparingTo("4950.000");
        Map<String, Object> applied = jdbcTemplate.queryForMap(
                "SELECT status, reviewed_by_user_id, fuel_dip_entry_id FROM nozzle_reading_adjustments WHERE id = ?",
                requested.get("id").asLong());
        assertThat(applied.get("status")).isEqualTo("APPLIED");
        assertThat(((Number) applied.get("reviewed_by_user_id")).longValue()).isEqualTo(setup.owner().getId());
        assertThat(jdbcTemplate.queryForObject("SELECT litres_removed FROM fuel_dip_entries WHERE id = ?",
                BigDecimal.class, applied.get("fuel_dip_entry_id"))).isEqualByComparingTo("50.000");
        assertThat(auditActions()).contains("METER_ADJUSTMENT_REQUESTED", "METER_ADJUSTMENT_APPROVED");
    }

    @Test
    void ownersForwardAdjustmentIsAppliedAtOnceWithItsDraw() throws Exception {
        adjust(setup.owner(), "1020.000", "Counted on the dial").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.unbilledLitres").value(20.0));

        assertThat(lastReading()).isEqualByComparingTo("1020.000");
        assertThat(tankStock()).isEqualByComparingTo("4980.000");
    }

    @Test
    void movingTheMeterBackStaysAnOwnerOrAdminCorrectionWithNoStockDraw() throws Exception {
        adjust(setup.manager(), "900.000", "Typo").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADJUSTMENT_OWNER_OR_ADMIN_ONLY"));

        adjust(setup.admin(), "900.000", "Typo").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.unbilledLitres").doesNotExist());

        assertThat(lastReading()).isEqualByComparingTo("900.000");
        assertThat(tankStock()).isEqualByComparingTo("5000.000");
        assertThat(auditActions()).contains("METER_READING_ADJUSTED");
        JsonNode history = json(perform(get("/api/pumps/{pumpId}/nozzles/{nozzleId}/reading-adjustments", setup.pumpId(),
                setup.nozzle().getId()), setup.owner()).andExpect(status().isOk()));
        assertThat(history.toString()).contains("Typo");
    }

    @Test
    void rejectedRequestLeavesTheMeterAndTheTank() throws Exception {
        long rejected = json(adjust(setup.manager(), "1050.000", "Repair").andExpect(status().isCreated())).get("id").asLong();
        decide(rejected, "REJECTED", "Not convinced").andExpect(jsonPath("$.succeeded").value(1));
        assertThat(lastReading()).isEqualByComparingTo("1000.000");
        assertThat(tankStock()).isEqualByComparingTo("5000.000");
    }

    private ResultActions adjust(User user, String newReading, String reason) throws Exception {
        return perform(post("/api/pumps/{pumpId}/nozzles/{nozzleId}/reading-adjustments", setup.pumpId(),
                setup.nozzle().getId()), user,
                Map.of("adjustmentType", "CUSTOM_READING", "newReading", new BigDecimal(newReading), "reason", reason));
    }

    private ResultActions decide(long adjustmentId, String action, String reason) throws Exception {
        Map<String, Object> body = reason == null
                ? Map.of("items", List.of(Map.of("kind", "METER_ADJUSTMENT", "id", adjustmentId)), "action", action)
                : Map.of("items", List.of(Map.of("kind", "METER_ADJUSTMENT", "id", adjustmentId)), "action", action,
                        "reason", reason);
        return perform(post("/api/pumps/{pumpId}/approvals/decisions", setup.pumpId()), setup.owner(), body)
                .andExpect(status().isOk());
    }

    private JsonNode pendingApproval(long adjustmentId) throws Exception {
        for (JsonNode item : json(perform(get("/api/pumps/{pumpId}/approvals", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()))) {
            if ("METER_ADJUSTMENT".equals(item.get("kind").asText()) && item.get("id").asLong() == adjustmentId) {
                return item;
            }
        }
        throw new AssertionError("Meter adjustment " + adjustmentId + " missing from the approval queue");
    }

    private void setReading(String reading) {
        jdbcTemplate.update("UPDATE nozzles SET last_reading = ?::numeric WHERE id = ?", reading, setup.nozzle().getId());
    }

    private BigDecimal lastReading() {
        return jdbcTemplate.queryForObject("SELECT last_reading FROM nozzles WHERE id = ?", BigDecimal.class,
                setup.nozzle().getId());
    }

    private BigDecimal tankStock() {
        return jdbcTemplate.queryForObject("SELECT current_stock FROM underground_tanks WHERE id = ?", BigDecimal.class,
                setup.nozzle().getTankId());
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList("SELECT action::text FROM audit_logs WHERE pump_id = ?", String.class,
                setup.pumpId());
    }
}
