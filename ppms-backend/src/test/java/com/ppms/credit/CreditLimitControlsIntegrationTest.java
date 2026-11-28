package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreditLimitControlsIntegrationTest extends ApiIntegrationTest {

    @Autowired private CreditClientRepository clientRepository;
    @Autowired private CreditOpeningBalanceRepository openingBalanceRepository;

    private PumpSetup setup;
    private long shiftId;

    @BeforeEach
    void createPumpWithOpenShift() throws Exception {
        setup = createPumpWithStock("5000");
        setup.pump().setDefaultCreditLimit(BigDecimal.ZERO);
        pumpLocationRepository.save(setup.pump());
        JsonNode opened = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful()));
        shiftId = opened.get("id").asLong();
    }

    @Test
    void newClientStartsWithThePumpsDefaultLimit() throws Exception {
        perform(put("/api/pumps/{pumpId}/credit-limit-settings", setup.pumpId()), setup.owner(),
                Map.of("defaultCreditLimit", new BigDecimal("2500"), "managerLimitCap", new BigDecimal("50000"),
                        "nearLimitPercent", 80))
                .andExpect(status().isOk());

        long clientId = createClient(null);
        long childId = createClient(clientId);

        assertThat(clientOf(clientId).get("creditLimit").decimalValue()).isEqualByComparingTo("2500");
        assertThat(clientOf(childId).get("creditLimit").decimalValue()).isEqualByComparingTo("0");
        sellOnCredit(childId, "2000.00").andExpect(status().is2xxSuccessful());
        assertThat(standingOf(childId).get("creditStatus").asText()).isEqualTo("NEAR_LIMIT");
        assertThat(standingOf(childId).get("availableCredit").decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void managerRaisesUpToTheCapButNotBeyondItAndCannotGiveUnlimitedCredit() throws Exception {
        long clientId = createClient(null);

        changeLimit(clientId, "50000", false, setup.manager()).andExpect(status().isOk());
        changeLimit(clientId, "50000.01", false, setup.manager()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_ABOVE_MANAGER_CAP"))
                .andExpect(jsonPath("$.params.cap").value("50000.00"));
        changeLimit(clientId, "0", true, setup.manager()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_UNLIMITED_OWNER_ONLY"));

        changeLimit(clientId, "80000", false, setup.admin()).andExpect(status().isOk());
        changeLimit(clientId, "60000", false, setup.manager()).andExpect(status().isOk());
        changeLimit(clientId, "0", true, setup.owner()).andExpect(status().isOk());
        changeLimit(clientId, "70000", false, setup.manager()).andExpect(status().isOk());

        assertThat(clientRepository.findById(clientId).orElseThrow().getCreditLimit()).isEqualByComparingTo("70000");
    }

    @Test
    void everyLimitChangeIsKeptWithWhoAndWhyNewestFirst() throws Exception {
        long clientId = createClient(null);
        perform(patch("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/credit-limit", setup.pumpId(), clientId),
                setup.owner(), Map.of("creditLimit", new BigDecimal("1000"), "unlimited", false, "reason", " "))
                .andExpect(status().isBadRequest());
        changeLimit(clientId, "1000", false, setup.manager()).andExpect(status().isOk());
        changeLimit(clientId, "0", true, setup.owner()).andExpect(status().isOk());
        changeLimit(clientId, "0", true, setup.owner()).andExpect(status().isOk());

        JsonNode history = json(perform(get("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/credit-limit-changes",
                setup.pumpId(), clientId), setup.accountant()).andExpect(status().isOk()));

        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("newUnlimited").asBoolean()).isTrue();
        assertThat(history.get(0).get("oldLimit").decimalValue()).isEqualByComparingTo("1000");
        assertThat(history.get(0).get("changedByName").asText()).isEqualTo(setup.owner().getFullName());
        assertThat(history.get(1).get("oldLimit").decimalValue()).isEqualByComparingTo("0");
        assertThat(history.get(1).get("newLimit").decimalValue()).isEqualByComparingTo("1000");
        assertThat(history.get(1).get("reason").asText()).isEqualTo("Limit review");
    }

    @Test
    void groupNearItsLimitShowsNearLimitAlongsideOverdueInTheAging() throws Exception {
        long clientId = createClient(null);
        changeLimit(clientId, "1000", false, setup.owner()).andExpect(status().isOk());
        sellOnCredit(clientId, "950.00").andExpect(status().is2xxSuccessful());

        assertThat(standingOf(clientId).get("creditStatus").asText()).isEqualTo("NEAR_LIMIT");
        JsonNode row = agingRowOf(clientId);
        assertThat(row.get("nearLimit").asBoolean()).isTrue();
        assertThat(row.get("unlimited").asBoolean()).isFalse();

        perform(put("/api/pumps/{pumpId}/credit-limit-settings", setup.pumpId()), setup.admin(),
                Map.of("defaultCreditLimit", BigDecimal.ZERO, "managerLimitCap", new BigDecimal("50000"),
                        "nearLimitPercent", 100))
                .andExpect(status().isOk());
        assertThat(standingOf(clientId).get("creditStatus").asText()).isEqualTo("OK");
    }

    @Test
    void openingBalanceCountsTowardsTheLimitAndNearLimit() throws Exception {
        long clientId = createClient(null);
        changeLimit(clientId, "1000", false, setup.owner()).andExpect(status().isOk());
        openingBalanceRepository.save(CreditOpeningBalance.builder()
                .pumpId(setup.pumpId()).clientId(clientId).amount(new BigDecimal("950.00"))
                .asOfDate(TestDates.today()).createdByUserId(setup.owner().getId()).build());

        assertThat(standingOf(clientId).get("creditStatus").asText()).isEqualTo("NEAR_LIMIT");
        sellOnCredit(clientId, "100.00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"));
    }

    @Test
    void onlyOwnerOrAdminChangesTheLimitSettingsAndManagersCanReadThem() throws Exception {
        Map<String, Object> settings = Map.of("defaultCreditLimit", new BigDecimal("100"),
                "managerLimitCap", new BigDecimal("1000"), "nearLimitPercent", 75);
        perform(put("/api/pumps/{pumpId}/credit-limit-settings", setup.pumpId()), setup.manager(), settings)
                .andExpect(status().isForbidden());
        perform(put("/api/pumps/{pumpId}/credit-limit-settings", setup.pumpId()), setup.owner(),
                Map.of("defaultCreditLimit", new BigDecimal("100"), "managerLimitCap", new BigDecimal("1000"),
                        "nearLimitPercent", 40))
                .andExpect(status().isBadRequest());
        perform(put("/api/pumps/{pumpId}/credit-limit-settings", setup.pumpId()), setup.owner(), settings)
                .andExpect(status().isOk());

        perform(get("/api/pumps/{pumpId}/credit-limit-settings", setup.pumpId()), setup.manager())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerLimitCap").value(1000.0))
                .andExpect(jsonPath("$.nearLimitPercent").value(75));
        perform(get("/api/pumps/{pumpId}/credit-limit-settings", setup.pumpId()), setup.operator())
                .andExpect(status().isForbidden());
    }

    private ResultActions changeLimit(long clientId, String limit, boolean unlimited, User user) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/credit-limit", setup.pumpId(), clientId),
                user, Map.of("creditLimit", new BigDecimal(limit), "unlimited", unlimited, "reason", "Limit review"));
    }

    private long createClient(Long parentClientId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        body.put("parentClientId", parentClientId);
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private ResultActions sellOnCredit(long clientId, String amount) throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), Map.of("clientName", "Client", "clientId", clientId,
                        "amount", new BigDecimal(amount), "fuelType", "PETROL"));
    }

    private JsonNode clientOf(long clientId) throws Exception {
        for (JsonNode client : json(perform(get("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()))) {
            if (client.get("id").asLong() == clientId) {
                return client;
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the client list");
    }

    private JsonNode standingOf(long clientId) throws Exception {
        for (JsonNode standing : json(perform(get("/api/pumps/{pumpId}/credit-clients/credit-standing", setup.pumpId()),
                setup.manager()).andExpect(status().isOk()))) {
            if (standing.get("clientId").asLong() == clientId) {
                return standing;
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the credit standing");
    }

    private JsonNode agingRowOf(long clientId) throws Exception {
        for (JsonNode row : json(perform(get("/api/pumps/{pumpId}/credit-ledger/aging", setup.pumpId()), setup.manager())
                .andExpect(status().isOk())).get("clients")) {
            if (row.get("clientId").asLong() == clientId) {
                return row;
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the aging");
    }
}
