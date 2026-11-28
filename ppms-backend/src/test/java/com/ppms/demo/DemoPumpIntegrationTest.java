package com.ppms.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.notification.NotificationService;
import com.ppms.pump.PumpLocation;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoPumpIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NotificationService notificationService;

    @Test
    void ownerCreatesADemoPumpWhoseFortnightOfSampleDataIsConsistent() throws Exception {
        PumpSetup realPump = createPumpWithStock("1000");
        Long demoPumpId = createDemoPump(realPump.owner());

        PumpLocation demo = pumpLocationRepository.findById(demoPumpId).orElseThrow();
        assertThat(demo.isDemo()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM shifts WHERE pump_id = ?", demoPumpId))
                .isEqualTo(DemoActivityBuilder.DAYS * 2L * 2L);
        assertThat(count("SELECT COUNT(*) FROM shifts WHERE pump_id = ? AND status = 'CLOSED_DISCREPANCY_PENDING'", demoPumpId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM tanker_deliveries WHERE pump_id = ?", demoPumpId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ancillary_sales WHERE pump_id = ?", demoPumpId)).isEqualTo(DemoActivityBuilder.DAYS);
        assertThat(count("SELECT COUNT(*) FROM shift_credit_entries e JOIN shifts s ON s.id = e.shift_id WHERE s.pump_id = ?",
                demoPumpId)).isPositive();

        BigDecimal tankStock = jdbcTemplate.queryForObject(
                "SELECT SUM(current_stock) FROM underground_tanks WHERE pump_id = ?", BigDecimal.class, demoPumpId);
        BigDecimal lotsRemaining = jdbcTemplate.queryForObject(
                "SELECT SUM(remaining_quantity) FROM inventory_lots WHERE pump_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, demoPumpId);
        assertThat(tankStock).isPositive().isEqualByComparingTo(lotsRemaining);

        User owner = realPump.owner();
        LocalDate yesterday = TestDates.today().minusDays(1);
        perform(post("/api/pumps/{pumpId}/balance-sheets/generate", demoPumpId), owner,
                Map.of("reportType", "DAY", "reportDate", yesterday.toString()))
                .andExpect(status().isCreated());
        JsonNode reconciliation = json(perform(get("/api/inventory/{pumpId}/reconciliation", demoPumpId)
                .param("from", TestDates.today().minusDays(DemoActivityBuilder.DAYS).toString())
                .param("to", yesterday.toString()), owner).andExpect(status().isOk()));
        assertThat(reconciliation.get("tanks")).hasSize(2);
        assertThat(reconciliation.get("tanks").get(0).get("summary").get("flaggedPeriodCount").asInt()).isZero();

        JsonNode clients = json(perform(get("/api/pumps/{pumpId}/credit-clients", demoPumpId), owner)
                .andExpect(status().isOk()));
        JsonNode busDepot = findByName(clients, "City Bus Depot (demo)");
        assertThat(busDepot.get("outstandingBalance").decimalValue()).isPositive();
    }

    @Test
    void demoPumpIsBadgedLeftOutOfTotalsAndKeptQuiet() throws Exception {
        PumpSetup realPump = createPumpWithStock("1000");
        Long demoPumpId = createDemoPump(realPump.owner());

        JsonNode pumps = json(perform(get("/api/pumps"), realPump.owner()).andExpect(status().isOk()));
        assertThat(pumps).anySatisfy(pump -> {
            assertThat(pump.get("id").asLong()).isEqualTo(demoPumpId);
            assertThat(pump.get("demo").asBoolean()).isTrue();
        });
        perform(get("/api/pumps/overview"), realPump.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pumps[?(@.pumpId == " + demoPumpId + ")].demo").value(true))
                .andExpect(jsonPath("$.totals.openShiftCount").value(0));

        assertThat(pumpLocationRepository.findAllIds()).doesNotContain(demoPumpId).contains(realPump.pumpId());
        notificationService.generateNotificationsForPump(demoPumpId);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE pump_id = ?", demoPumpId)).isZero();
    }

    @Test
    void deletingTheDemoPumpRemovesAllItsDataAndLeavesTheRealPumpAlone() throws Exception {
        PumpSetup realPump = createPumpWithStock("1000");
        long realShifts = count("SELECT COUNT(*) FROM shifts WHERE pump_id = ?", realPump.pumpId());
        long realUsers = count("SELECT COUNT(*) FROM users WHERE assigned_pump_id = ?", realPump.pumpId());
        Long demoPumpId = createDemoPump(realPump.owner());

        perform(delete("/api/demo-pump"), realPump.owner()).andExpect(status().isNoContent());

        assertThat(pumpLocationRepository.findById(demoPumpId)).isEmpty();
        assertThat(rowsStillReferencingPump(demoPumpId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM users WHERE assigned_pump_id = ?", demoPumpId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM shifts WHERE pump_id = ?", realPump.pumpId())).isEqualTo(realShifts);
        assertThat(count("SELECT COUNT(*) FROM users WHERE assigned_pump_id = ?", realPump.pumpId())).isEqualTo(realUsers);
        assertThat(userRepository.findById(realPump.owner().getId())).isPresent();
        assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'DEMO_PUMP_DELETED' AND entity_id = ?",
                demoPumpId.toString())).isEqualTo(1);

        createDemoPump(realPump.owner());
    }

    @Test
    void superAdminCreatesAndDeletesAnOwnersDemoPump() throws Exception {
        PumpSetup realPump = createPumpWithStock("0");
        User superAdmin = saveUser(UserRole.SUPER_ADMIN, null);

        perform(post("/api/super-admin/owners/{ownerId}/demo-pump", realPump.owner().getId()), superAdmin)
                .andExpect(status().isCreated());
        assertThat(pumpLocationRepository.findByOwnerIdAndDemoTrue(realPump.owner().getId())).isPresent();
        perform(get("/api/super-admin/owners"), superAdmin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ownerId == " + realPump.owner().getId() + ")].pumps[?(@.demo == true)]").exists());

        perform(delete("/api/super-admin/owners/{ownerId}/demo-pump", realPump.owner().getId()), superAdmin)
                .andExpect(status().isNoContent());
        assertThat(pumpLocationRepository.findByOwnerIdAndDemoTrue(realPump.owner().getId())).isEmpty();
    }

    @Test
    void onlyAnOwnerOrSuperAdminManagesDemoPumps() throws Exception {
        PumpSetup realPump = createPumpWithStock("0");

        perform(post("/api/demo-pump"), realPump.admin()).andExpect(status().isForbidden());
        perform(post("/api/demo-pump"), realPump.manager()).andExpect(status().isForbidden());
        perform(post("/api/super-admin/owners/{ownerId}/demo-pump", realPump.owner().getId()), realPump.owner())
                .andExpect(status().isForbidden());
    }

    private Long createDemoPump(User owner) throws Exception {
        return json(perform(post("/api/demo-pump"), owner).andExpect(status().isCreated())).get("pumpId").asLong();
    }

    private long count(String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private long rowsStillReferencingPump(Long pumpId) {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT c.table_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_name = c.table_name AND t.table_schema = c.table_schema
                WHERE c.table_schema = current_schema() AND c.column_name = 'pump_id' AND t.table_type = 'BASE TABLE'
                """, String.class);
        return tables.stream().mapToLong(table -> count("SELECT COUNT(*) FROM \"" + table + "\" WHERE pump_id = ?", pumpId)).sum();
    }

    private static JsonNode findByName(JsonNode list, String name) {
        for (JsonNode item : list) {
            if (name.equals(item.get("name").asText())) {
                return item;
            }
        }
        throw new AssertionError("No entry named " + name);
    }
}
