package com.ppms.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ppms.onboarding.StaffImportIntegrationTest.freePhone;
import static com.ppms.onboarding.StaffImportIntegrationTest.issues;
import static com.ppms.onboarding.StaffImportIntegrationTest.outcomes;
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
    void parentProblemsAndPhoneClashesAreReportedPerCell() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        String sharedPhone = freePhone();
        perform(post("/api/pumps/{pumpId}/imports/credit-clients", setup.pumpId()), setup.owner(),
                rows(row(2, "Existing Group", sharedPhone, null, Map.of()), row(3, "Existing Sub", freePhone(), "Existing Group", Map.of())))
                .andExpect(status().isOk());

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/credit-clients/preview", setup.pumpId()), setup.owner(), rows(
                row(2, "Orphan", freePhone(), "Nobody", Map.of()),
                row(3, "Too deep", freePhone(), "Existing Sub", Map.of()),
                row(4, "New Root", sharedPhone, null, Map.of()),
                row(5, "Child", freePhone(), "Existing Group", Map.of("credit_limit", "100")),
                row(6, "Bad <name>", freePhone(), null, Map.of("gstin", "123"))))
                .andExpect(status().isOk()));

        assertThat(outcomes(report)).containsOnly("ERROR");
        assertThat(issues(report, 0)).containsExactly("parent_name:IMPORT_PARENT_NOT_FOUND");
        assertThat(issues(report, 1)).containsExactly("parent_name:CREDIT_SUB_ACCOUNT_NESTING_NOT_ALLOWED");
        assertThat(issues(report, 2)).containsExactly("phone:CREDIT_CLIENT_PHONE_TAKEN");
        assertThat(issues(report, 3)).containsExactly("credit_limit:IMPORT_CREDIT_LIMIT_ON_SUB_ACCOUNT");
        assertThat(issues(report, 4)).containsExactlyInAnyOrder("name:IMPORT_TEXT_HAS_ANGLE_BRACKETS", "gstin:GSTIN_INVALID");
    }

    @Test
    void duplicatesInTheFileAreErrorsAndAPhoneCannotSpanTwoGroups() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        String phone = freePhone();

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/credit-clients/preview", setup.pumpId()), setup.owner(), rows(
                row(2, "Alpha", phone, null, Map.of()),
                row(3, "ALPHA", freePhone(), null, Map.of()),
                row(4, "Beta", phone, null, Map.of()),
                row(5, "Alpha Sub", phone, "Alpha", Map.of())))
                .andExpect(status().isOk()));

        assertThat(outcomes(report)).containsExactly("CREATE", "ERROR", "ERROR", "CREATE");
        assertThat(issues(report, 1)).containsExactly("name:IMPORT_DUPLICATE_IN_FILE");
        assertThat(issues(report, 2)).containsExactly("phone:IMPORT_PHONE_IN_OTHER_GROUP");
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

    @Test
    void limitCellTakesAnAmountOrUnlimitedAndBlankTakesThePumpDefaultOnlyWhenItAllowsCredit() throws Exception {
        PumpSetup setup = createPumpWithStock("0");

        perform(post("/api/pumps/{pumpId}/imports/credit-clients", setup.pumpId()), setup.owner(), rows(
                row(2, "Trusted Fleet", freePhone(), null, Map.of("credit_limit", "Unlimited")),
                row(3, "Cash Only", freePhone(), null, Map.of("credit_limit", "0")),
                row(4, "Default Limit", freePhone(), null, Map.of())))
                .andExpect(status().isOk());

        assertThat(clientNamed(setup, "Trusted Fleet").isUnlimitedCredit()).isTrue();
        assertThat(clientNamed(setup, "Cash Only").isUnlimitedCredit()).isFalse();
        assertThat(clientNamed(setup, "Cash Only").getCreditLimit()).isEqualByComparingTo("0");
        assertThat(clientNamed(setup, "Default Limit").getCreditLimit()).isEqualByComparingTo(TEST_DEFAULT_CREDIT_LIMIT);

        setup.pump().setDefaultCreditLimit(BigDecimal.ZERO);
        pumpLocationRepository.save(setup.pump());
        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/credit-clients/preview", setup.pumpId()),
                setup.owner(), rows(
                        row(2, "No Limit Given", freePhone(), null, Map.of()),
                        row(3, "Words", freePhone(), null, Map.of("credit_limit", "lots"))))
                .andExpect(status().isOk()));
        assertThat(issues(report, 0)).containsExactly("credit_limit:IMPORT_CREDIT_LIMIT_REQUIRED");
        assertThat(issues(report, 1)).containsExactly("credit_limit:IMPORT_NUMBER_INVALID");
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
