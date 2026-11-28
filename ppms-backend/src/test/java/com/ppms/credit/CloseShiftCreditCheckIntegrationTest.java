package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Credit captured at shift close is checked like a credit sale during the shift, with an audited Owner/Admin override. */
class CloseShiftCreditCheckIntegrationTest extends ApiIntegrationTest {

    @Autowired private CreditCloseExceptionRepository closeExceptionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private long shiftId;

    @BeforeEach
    void createPumpWithOpenShift() throws Exception {
        setup = createPumpWithStock("5000");
        JsonNode opened = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful()));
        shiftId = opened.get("id").asLong();
    }

    @Test
    void ownerOrAdminClosesWithBlockedCreditGivingAReasonWhileAManagerIsRefused() throws Exception {
        long clientId = createClient("Late Co");
        backdate(sellOnCredit(clientId, "100.00"), 40);
        String path = "/api/pumps/{pumpId}/shifts/{id}/close";

        perform(post(path, setup.pumpId(), shiftId), setup.manager(), closeWithCredit(clientId, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_CLOSE_BLOCKED_CLIENTS"));
        perform(post(path, setup.pumpId(), shiftId), setup.manager(), closeWithCredit(clientId, true, "Will pay tomorrow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_CLOSE_OVERRIDE_OWNER_ONLY"));
        perform(post(path, setup.pumpId(), shiftId), setup.admin(), closeWithCredit(clientId, true, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_CLOSE_OVERRIDE_REASON_REQUIRED"));

        perform(post(path, setup.pumpId(), shiftId), setup.admin(), closeWithCredit(clientId, true, "Will pay tomorrow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));
        assertThat(closeExceptionRepository.findAll()).filteredOn(exception -> exception.getClientId() == clientId)
                .singleElement()
                .satisfies(exception -> {
                    assertThat(exception.getShiftId()).isEqualTo(shiftId);
                    assertThat(exception.getAllowedByUserId()).isEqualTo(setup.admin().getId());
                    assertThat(exception.getReason()).isEqualTo("Will pay tomorrow");
                });
    }

    private Map<String, Object> closeWithCredit(long clientId, Boolean allowBlockedCredit, String reason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        request.put("cashCollected", new BigDecimal("850.00"));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", new BigDecimal("150.00"));
        request.put("creditEntries", List.of(Map.of("clientName", "Late Co", "clientId", clientId,
                "amount", new BigDecimal("50.00"), "fuelType", "PETROL")));
        request.put("allowBlockedCredit", allowBlockedCredit);
        request.put("blockedCreditReason", reason);
        return request;
    }

    private long createClient(String name) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(),
                Map.of("name", name, "phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L)))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private long sellOnCredit(long clientId, String amount) throws Exception {
        JsonNode shift = json(perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), Map.of("clientName", "Client", "clientId", clientId,
                        "amount", new BigDecimal(amount), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful()));
        JsonNode entries = shift.get("creditEntries");
        return entries.get(entries.size() - 1).get("id").asLong();
    }

    private void backdate(long entryId, int daysAgo) {
        jdbcTemplate.update("UPDATE shift_credit_entries SET created_at = ?, sale_date = ? WHERE id = ?",
                TestDates.now().minusDays(daysAgo), TestDates.today().minusDays(daysAgo), entryId);
    }
}
