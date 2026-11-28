package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreditClientEditIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("0");
    }

    @Test
    void creatingEditingAndDisablingAClientIsAuditedWithWhatChangedAndThePhoneMasked() throws Exception {
        String phone = uniquePhone();
        long clientId = createClient(phone);
        String newPhone = uniquePhone();

        update(clientId, setup.owner(), Map.of("name", "Renamed Fleet", "phone", newPhone, "gstin", "27AAPFU0939F1ZV"))
                .andExpect(status().isOk());
        setActive(clientId, false, setup.manager()).andExpect(status().isOk());

        List<String> descriptions = jdbcTemplate.queryForList(
                "SELECT action || ': ' || description FROM audit_logs WHERE pump_id = ? AND entity_type = 'CreditClient'"
                        + " AND entity_id = ? ORDER BY id",
                String.class, setup.pumpId(), String.valueOf(clientId));
        assertThat(descriptions).hasSize(3);
        assertThat(descriptions.get(0)).startsWith("CREDIT_CLIENT_CREATED").contains("credit limit ₹" + TEST_DEFAULT_CREDIT_LIMIT);
        assertThat(descriptions.get(1)).startsWith("CREDIT_CLIENT_UPDATED")
                .contains("→ Renamed Fleet", "******" + newPhone.substring(6), "GSTIN none → 27AAPFU0939F1ZV");
        assertThat(descriptions.get(2)).isEqualTo("CREDIT_CLIENT_STATUS_CHANGED: Credit client Renamed Fleet disabled");
        assertThat(String.join(" ", descriptions)).doesNotContain(phone, newPhone);
    }

    private long createClient(String phone) throws Exception {
        JsonNode client = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(),
                Map.of("name", "Fleet " + shortId(), "phone", phone))
                .andExpect(status().is2xxSuccessful()));
        return client.get("id").asLong();
    }

    private ResultActions update(long clientId, User user, Map<String, Object> body) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/credit-clients/{clientId}", setup.pumpId(), clientId), user, body);
    }

    private ResultActions setActive(long clientId, boolean active, User user) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/credit-clients/{clientId}/status", setup.pumpId(), clientId), user,
                Map.of("isActive", active));
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L);
    }
}
