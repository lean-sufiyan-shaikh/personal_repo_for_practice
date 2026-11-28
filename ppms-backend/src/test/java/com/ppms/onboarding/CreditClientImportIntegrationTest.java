package com.ppms.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ppms.onboarding.StaffImportIntegrationTest.freePhone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreditClientImportIntegrationTest extends ApiIntegrationTest {

    @Autowired private CreditClientRepository clientRepository;

    @Test
    void clientsAndSubAccountsAreCreatedWithOpeningBalancesInTheirBalanceLedgerAndAgeing() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        String fleetPhone = freePhone();
        LocalDate goLive = TestDates.today().minusDays(40);

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/credit-clients", setup.pumpId()), setup.owner(), rows(
                row(2, "Sharma Transport", fleetPhone, null, Map.of("credit_limit", "50000", "opening_balance", "12500.50",
                        "opening_balance_date", goLive.toString(), "gstin", "27abcde1234f1z5")),
                row(3, "Sharma Tankers", fleetPhone, "sharma transport", Map.of("opening_balance", "2000")),
                row(4, "Patil Farms", freePhone(), null, Map.of())))
                .andExpect(status().isOk()));

        assertThat(report.get("createCount").asInt()).isEqualTo(3);
        CreditClient parent = clientNamed(setup, "Sharma Transport");
        CreditClient sub = clientNamed(setup, "Sharma Tankers");
        assertThat(parent.getCreditLimit()).isEqualByComparingTo("50000");
        assertThat(parent.getGstin()).isEqualTo("27ABCDE1234F1Z5");
        assertThat(sub.getParentClientId()).isEqualTo(parent.getId());

        JsonNode clients = json(perform(get("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.owner()));
        assertThat(outstandingOf(clients, "Sharma Transport")).isEqualTo("14500.50");
        assertThat(outstandingOf(clients, "Sharma Tankers")).isEqualTo("2000.00");
        assertThat(outstandingOf(clients, "Patil Farms")).isEqualTo("0.00");

        JsonNode ledger = json(perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/transactions/all",
                setup.pumpId(), parent.getId()), setup.owner()).andExpect(status().isOk()));
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).get("type").asText()).isEqualTo("OPENING_BALANCE");
        assertThat(ledger.get(0).get("businessDate").asText()).isEqualTo(goLive.toString());
        assertThat(ledger.get(0).get("amount").decimalValue()).isEqualByComparingTo("12500.50");

        perform(get("/api/pumps/{pumpId}/credit-ledger/aging", setup.pumpId()), setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients[?(@.name == 'Sharma Transport')].days31To60").value(12500.50))
                .andExpect(jsonPath("$.clients[?(@.name == 'Sharma Transport')].upTo30Days").value(2000.0))
                .andExpect(jsonPath("$.clients[?(@.name == 'Sharma Transport')].daysOverdue").value(10))
                .andExpect(jsonPath("$.clients[?(@.name == 'Sharma Transport')].oldestUnpaidSaleDate").value(goLive.toString()))
                .andExpect(jsonPath("$.clients[?(@.name == 'Patil Farms')].daysOverdue").value(0));
    }

    @Test
    void uploadingTheSameFileTwiceAddsNoClientAndNoSecondOpeningBalance() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        Map<String, Object> file = rows(row(2, "Repeat Co", freePhone(), null, Map.of("opening_balance", "1000")),
                row(3, "Repeat Branch", freePhone(), "Repeat Co", Map.of()));
        perform(post("/api/pumps/{pumpId}/imports/credit-clients", setup.pumpId()), setup.owner(), file).andExpect(status().isOk());

        JsonNode second = json(perform(post("/api/pumps/{pumpId}/imports/credit-clients", setup.pumpId()), setup.owner(), file)
                .andExpect(status().isOk()));

        assertThat(second.get("skipCount").asInt()).isEqualTo(2);
        assertThat(clientRepository.findByPumpIdOrderByNameAsc(setup.pumpId())).hasSize(2);
        JsonNode clients = json(perform(get("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.owner()));
        assertThat(outstandingOf(clients, "Repeat Co")).isEqualTo("1000.00");
    }

    private CreditClient clientNamed(PumpSetup setup, String name) {
        return clientRepository.findByPumpIdOrderByNameAsc(setup.pumpId()).stream()
                .filter(client -> client.getName().equals(name)).findFirst().orElseThrow();
    }

    private static String outstandingOf(JsonNode clients, String name) {
        for (JsonNode client : clients) {
            if (client.get("name").asText().equals(name)) {
                return client.get("outstandingBalance").decimalValue().setScale(2).toPlainString();
            }
        }
        throw new AssertionError("No client " + name);
    }

    @SafeVarargs
    private static Map<String, Object> rows(Map<String, Object>... rows) {
        return Map.of("rows", List.of(rows));
    }

    private static Map<String, Object> row(int rowNumber, String name, String phone, String parentName,
                                           Map<String, String> extra) {
        Map<String, Object> row = new HashMap<>();
        row.put("rowNumber", rowNumber);
        row.put("name", name);
        row.put("phone", phone);
        row.put("parentName", parentName);
        row.put("creditLimit", extra.get("credit_limit"));
        row.put("openingBalance", extra.get("opening_balance"));
        row.put("openingBalanceDate", extra.get("opening_balance_date"));
        row.put("gstin", extra.get("gstin"));
        return row;
    }
}
