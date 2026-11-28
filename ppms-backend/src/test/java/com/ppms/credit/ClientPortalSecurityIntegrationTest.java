package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClientPortalSecurityIntegrationTest extends ApiIntegrationTest {

    private static final String GOOD_PIN = "482913";
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    @Autowired private CreditClientRepository clientRepository;
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
    void fiveWrongPinsLockThePhoneEvenForTheRightPinAndTheOwnerIsNotified() throws Exception {
        String phone = uniquePhone();
        long clientId = createClient(null, phone);
        setPin(clientId, GOOD_PIN).andExpect(status().isNoContent());

        for (int attempt = 1; attempt <= 5; attempt++) {
            lookUp(phone, "111111").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_NOT_MATCHED"));
        }
        lookUp(phone, GOOD_PIN).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_BALANCE_LOOKUPS"))
                .andExpect(jsonPath("$.params.minutes").value(15));

        assertThat(notificationTypes(setup.owner())).contains("CREDIT_PORTAL_LOCKED");
        assertThat(notificationTypes(setup.manager())).doesNotContain("CREDIT_PORTAL_LOCKED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action = 'CREDIT_PORTAL_LOCKED' AND entity_id = ?",
                Long.class, setup.pumpId(), String.valueOf(clientId))).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForList("SELECT phone_hash FROM credit_portal_attempts WHERE pump_id = ?",
                String.class, setup.pumpId())).singleElement().satisfies(hash -> assertThat(hash).doesNotContain(phone));
        lookUp(uniquePhone(), GOOD_PIN).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_NOT_MATCHED"))
                .andExpect(jsonPath("$.message").value("No credit account matches these details."));
    }

    @Test
    void rightPinAfterTheLockoutEndsClearsTheCount() throws Exception {
        String phone = uniquePhone();
        long clientId = createClient(null, phone);
        setPin(clientId, GOOD_PIN).andExpect(status().isNoContent());

        failFiveTimes(phone);
        endLockout();
        lookUp(phone, GOOD_PIN).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM credit_portal_attempts WHERE pump_id = ?",
                Long.class, setup.pumpId())).isZero();
    }

    @Test
    void newPinMustBeSixDigitsAndHardToGuess() throws Exception {
        long clientId = createClient(null, uniquePhone());

        setPin(clientId, "4821").andExpect(status().isBadRequest());
        for (String trivial : List.of("111111", "123456", "987654", "121212", "123123", "112233")) {
            setPin(clientId, trivial).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PORTAL_PIN_TOO_EASY"));
        }
        setPin(clientId, GOOD_PIN).andExpect(status().isNoContent());

        JsonNode client = clientOf(clientId);
        assertThat(client.get("hasPortalPin").asBoolean()).isTrue();
        assertThat(client.get("weakPortalPin").asBoolean()).isFalse();
    }

    @Test
    void fourDigitPinSetBeforeTheRuleKeepsWorkingAndShowsAsWeakOnceUsed() throws Exception {
        String phone = uniquePhone();
        long clientId = createClient(null, phone);
        CreditClient client = clientRepository.findById(clientId).orElseThrow();
        client.setPortalPinHash(passwordEncoder.encode("4821"));
        clientRepository.save(client);
        assertThat(clientOf(clientId).get("weakPortalPin").asBoolean()).isFalse();

        lookUp(phone, "4821").andExpect(status().isOk());

        assertThat(clientOf(clientId).get("weakPortalPin").asBoolean()).isTrue();
    }

    @Test
    void mainAccountSeesTheGroupBalanceAndLimitAndASubAccountOnlyItsOwnUsage() throws Exception {
        saveUpiDetails();
        String parentPhone = uniquePhone();
        String childPhone = uniquePhone();
        long parentId = createClient(null, parentPhone);
        long childId = createClient(parentId, childPhone);
        perform(patch("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/credit-limit", setup.pumpId(), parentId),
                setup.owner(), Map.of("creditLimit", new BigDecimal("5000"), "reason", "Fleet limit"))
                .andExpect(status().isOk());
        sellOnCredit(parentId, "200.00");
        sellOnCredit(childId, "300.00");
        setPin(parentId, GOOD_PIN).andExpect(status().isNoContent());
        setPin(childId, "582614").andExpect(status().isNoContent());

        lookUp(parentPhone, GOOD_PIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingAmount").value(500.00))
                .andExpect(jsonPath("$.creditLimit").value(5000.00))
                .andExpect(jsonPath("$.unlimited").value(false))
                .andExpect(jsonPath("$.subAccount").value(false))
                .andExpect(jsonPath("$.upiPayLink").value(org.hamcrest.Matchers.containsString("am=500.00")));
        lookUp(childPhone, "582614").andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingAmount").value(300.00))
                .andExpect(jsonPath("$.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.subAccount").value(true))
                .andExpect(jsonPath("$.upiPayLink").doesNotExist());
    }

    private void failFiveTimes(String phone) throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            lookUp(phone, "999999").andExpect(status().isNotFound());
        }
    }

    private void endLockout() {
        jdbcTemplate.update("UPDATE credit_portal_attempts SET locked_until = ? WHERE pump_id = ?",
                TestDates.now().minusMinutes(1), setup.pumpId());
    }

    private void saveUpiDetails() throws Exception {
        perform(put("/api/pumps/{pumpId}/collection-settings", setup.pumpId()), setup.owner(),
                Map.of("reminderStageDays", List.of(7), "creditHoldDays", 45, "upiId", "ramfuels@okaxis",
                        "upiPayeeName", "Ram Fuels", "backdatedPaymentApprovalDays", 7))
                .andExpect(status().isOk());
    }

    private ResultActions setPin(long clientId, String pin) throws Exception {
        return perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId),
                setup.owner(), Map.of("pin", pin));
    }

    private ResultActions lookUp(String phone, String pin) throws Exception {
        String address = "198.18." + (NEXT_ADDRESS.incrementAndGet() / 250) + "." + (NEXT_ADDRESS.get() % 250);
        return mockMvc.perform(post("/api/public/credit-balance")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("pumpId", setup.pumpId(), "phone", phone, "pin", pin))));
    }

    private long createClient(Long parentClientId, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", phone);
        body.put("parentClientId", parentClientId);
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private void sellOnCredit(long clientId, String amount) throws Exception {
        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), Map.of("clientName", "Client", "clientId", clientId,
                        "amount", new BigDecimal(amount), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful());
    }

    private JsonNode clientOf(long clientId) throws Exception {
        for (JsonNode client : json(perform(get("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()))) {
            if (client.get("id").asLong() == clientId) {
                return client;
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the client list");
    }

    private List<String> notificationTypes(com.ppms.user.User user) throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/notifications", setup.pumpId()), user).andExpect(status().isOk()))
                .findValuesAsText("type");
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L);
    }
}
