package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreditLedgerIntegrationTest extends ApiIntegrationTest {

    @Autowired private CreditClientRepository creditClientRepository;

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
    void paymentByAManagerWithNoThresholdIsApprovedAndLowersTheBalance() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "1000.00");

        JsonNode payment = json(recordPayment(clientId, "400.00").andExpect(status().is2xxSuccessful()));

        assertThat(payment.get("paymentApprovalStatus").asText()).isEqualTo("APPROVED");
        assertThat(outstandingOf(clientId)).isEqualByComparingTo("600.00");
    }

    @Test
    void paymentLargerThanWhatIsOwedIsRefused() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "300.00");

        recordPayment(clientId, "300.01").andExpect(status().isBadRequest());

        assertThat(outstandingOf(clientId)).isEqualByComparingTo("300.00");
    }

    @Test
    void paymentAboveTheThresholdWaitsForTheOwnerAndCountsOnlyOnceApproved() throws Exception {
        setExpenseApprovalThreshold("100.00");
        long clientId = createClient(null);
        sellOnCredit(clientId, "1000.00");

        JsonNode payment = json(recordPayment(clientId, "500.00").andExpect(status().is2xxSuccessful()));
        long paymentId = payment.get("id").asLong();
        assertThat(payment.get("paymentApprovalStatus").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(outstandingOf(clientId)).isEqualByComparingTo("1000.00");

        JsonNode pending = json(perform(get("/api/pumps/{pumpId}/credit-ledger/payments/pending", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        assertThat(pending).extracting(node -> node.get("id").asLong()).contains(paymentId);

        perform(patch("/api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/approve", setup.pumpId(), paymentId),
                setup.manager(), Map.of("status", "APPROVED"))
                .andExpect(status().isForbidden());
        perform(patch("/api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/approve", setup.pumpId(), paymentId),
                setup.owner(), Map.of("status", "APPROVED"))
                .andExpect(status().isOk());

        assertThat(outstandingOf(clientId)).isEqualByComparingTo("500.00");
    }

    @Test
    void rejectedPaymentLeavesTheBalanceUnchanged() throws Exception {
        setExpenseApprovalThreshold("100.00");
        long clientId = createClient(null);
        sellOnCredit(clientId, "1000.00");
        long paymentId = json(recordPayment(clientId, "500.00")).get("id").asLong();

        perform(patch("/api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/approve", setup.pumpId(), paymentId),
                setup.owner(), Map.of("status", "REJECTED"))
                .andExpect(status().isOk());

        assertThat(outstandingOf(clientId)).isEqualByComparingTo("1000.00");
        JsonNode transactions = json(perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/transactions", setup.pumpId(), clientId),
                setup.owner()).andExpect(status().isOk()));
        assertThat(transactions.get("content")).anySatisfy(row -> {
            assertThat(row.get("type").asText()).isEqualTo("PAYMENT");
            assertThat(row.get("countedInBalance").asBoolean()).isFalse();
        });
    }

    @Test
    void creditSaleBeyondTheClientsLimitIsRefused() throws Exception {
        long clientId = createClient(null);
        perform(patch("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/credit-limit", setup.pumpId(), clientId),
                setup.manager(), Map.of("creditLimit", new BigDecimal("500.00")))
                .andExpect(status().isOk());
        sellOnCredit(clientId, "400.00");

        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.operator(),
                creditEntry(clientId, "150.00"))
                .andExpect(status().isBadRequest());

        assertThat(outstandingOf(clientId)).isEqualByComparingTo("400.00");
    }

    @Test
    void parentBalanceIncludesItsSubAccounts() throws Exception {
        long parentId = createClient(null);
        long childId = createClient(parentId);
        sellOnCredit(parentId, "100.00");
        sellOnCredit(childId, "250.00");

        assertThat(outstandingOf(parentId)).isEqualByComparingTo("350.00");
        assertThat(outstandingOf(childId)).isEqualByComparingTo("250.00");
    }

    @Test
    void reassigningACreditEntryMovesItsAmountToAnotherSubAccountOfTheSameGroup() throws Exception {
        long parent = createClient(null);
        long fromClient = createClient(parent);
        long toClient = createClient(parent);
        long entryId = sellOnCredit(fromClient, "220.00");

        perform(post("/api/pumps/{pumpId}/credit-ledger/entries/{entryId}/reassign", setup.pumpId(), entryId),
                setup.manager(), Map.of("toClientId", toClient, "reason", "Billed to wrong vehicle"))
                .andExpect(status().is2xxSuccessful());

        assertThat(outstandingOf(fromClient)).isEqualByComparingTo("0");
        assertThat(outstandingOf(toClient)).isEqualByComparingTo("220.00");
        assertThat(outstandingOf(parent)).isEqualByComparingTo("220.00");
    }

    @Test
    void reassigningACreditEntryToAnUnrelatedClientIsRefused() throws Exception {
        long fromClient = createClient(null);
        long unrelated = createClient(null);
        long entryId = sellOnCredit(fromClient, "220.00");

        perform(post("/api/pumps/{pumpId}/credit-ledger/entries/{entryId}/reassign", setup.pumpId(), entryId),
                setup.manager(), Map.of("toClientId", unrelated, "reason", "Wrong fleet"))
                .andExpect(status().isBadRequest());

        assertThat(outstandingOf(fromClient)).isEqualByComparingTo("220.00");
    }

    @Test
    void statementPageEscapesTheClientNameAndForbidsScripts() throws Exception {
        CreditClient stored = creditClientRepository.save(CreditClient.builder()
                .pumpId(setup.pumpId())
                .name("</title><script>alert(1)</script>")
                .build());

        perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/statement", setup.pumpId(), stored.getId()), setup.owner())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(containsString("&lt;/title&gt;&lt;script&gt;")));

        perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(),
                Map.of("name", "<b>Bold</b>", "phone", uniquePhone()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paymentDatedInTheFutureIsRejected() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "100.00");

        perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId), setup.owner(),
                Map.of("amount", new BigDecimal("50.00"), "paymentMode", "CASH", "paidAt", LocalDate.now().plusDays(3).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountantCanReadTheLedgerButCannotRecordPayments() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "500.00");

        perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/transactions", setup.pumpId(), clientId), setup.accountant())
                .andExpect(status().isOk());
        perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/statement", setup.pumpId(), clientId), setup.accountant())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("500.00")));
        perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId), setup.accountant(),
                paymentBody("100.00"))
                .andExpect(status().isForbidden());
        perform(get("/api/pumps/{pumpId}/credit-ledger", setup.pumpId()), setup.operator())
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyOwnerOrAdminCanGrantACreditExtension() throws Exception {
        long clientId = createClient(null);
        Map<String, Object> extension = Map.of("extensionType", "AMOUNT_EXTENSION",
                "extensionAmount", new BigDecimal("1000.00"),
                "expiryDate", LocalDate.now().plusDays(7).toString(),
                "reason", "Festival season");

        perform(post("/api/pumps/{pumpId}/credit-clients/{clientId}/extensions", setup.pumpId(), clientId),
                setup.manager(), extension)
                .andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/credit-clients/{clientId}/extensions", setup.pumpId(), clientId),
                setup.owner(), extension)
                .andExpect(status().is2xxSuccessful());

        JsonNode active = json(perform(get("/api/pumps/{pumpId}/credit-clients/{clientId}/extensions/active", setup.pumpId(), clientId),
                setup.manager()).andExpect(status().isOk()));
        assertThat(active.toString()).contains("AMOUNT_EXTENSION");
    }

    @Test
    void publicBalanceLookupNeedsTheClientsPinAndHidesTheName() throws Exception {
        String phone = uniquePhone();
        long clientId = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(),
                Map.of("name", "Portal Client", "phone", phone))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        sellOnCredit(clientId, "75.00");

        lookUpBalance(phone, "4321").andExpect(status().isNotFound());

        perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId),
                setup.manager(), Map.of("pin", "4321"))
                .andExpect(status().isForbidden());
        perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId),
                setup.owner(), Map.of("pin", "4321"))
                .andExpect(status().isNoContent());

        lookUpBalance(phone, "9999").andExpect(status().isNotFound())
                .andExpect(content().string(containsString("No credit account matches these details.")));
        lookUpBalance(phone, "4321")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingAmount").value(75.00))
                .andExpect(jsonPath("$.clientName").doesNotExist());

        perform(delete("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId), setup.owner())
                .andExpect(status().isNoContent());
        lookUpBalance(phone, "4321").andExpect(status().isNotFound());
    }

    @Test
    void publicBalanceLookupIsRefusedForASuspendedPump() throws Exception {
        String phone = uniquePhone();
        long clientId = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(),
                Map.of("name", "Suspended Pump Client", "phone", phone))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId),
                setup.owner(), Map.of("pin", "2468"))
                .andExpect(status().isNoContent());
        setup.pump().setEnabled(false);
        pumpLocationRepository.save(setup.pump());

        lookUpBalance(phone, "2468").andExpect(status().isNotFound());
    }

    @Test
    void repeatedLookupsForOnePhoneAreLimitedEvenFromDifferentAddresses() throws Exception {
        String phone = uniquePhone();
        for (int attempt = 1; attempt <= 5; attempt++) {
            lookUpBalanceFrom(phone, "1111", "203.0.113." + attempt).andExpect(status().isNotFound());
        }
        lookUpBalanceFrom(phone, "1111", "203.0.113.99").andExpect(status().isTooManyRequests());
    }

    @Test
    void clientWithLedgerHistoryCannotBeDeletedButANewOneCan() throws Exception {
        long withHistory = createClient(null);
        sellOnCredit(withHistory, "10.00");
        long withoutHistory = createClient(null);

        perform(delete("/api/pumps/{pumpId}/credit-clients/{clientId}", setup.pumpId(), withHistory), setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("deactivate instead")));
        perform(delete("/api/pumps/{pumpId}/credit-clients/{clientId}", setup.pumpId(), withoutHistory), setup.owner())
                .andExpect(status().isNoContent());
    }

    private ResultActions lookUpBalance(String phone, String pin) throws Exception {
        return lookUpBalanceFrom(phone, pin, "198.51.100." + (Math.abs(phone.hashCode()) % 250));
    }

    private ResultActions lookUpBalanceFrom(String phone, String pin, String remoteAddress) throws Exception {
        return mockMvc.perform(post("/api/public/credit-balance")
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("pumpId", setup.pumpId(), "phone", phone, "pin", pin))));
    }

    private long createClient(Long parentClientId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", uniquePhone());
        body.put("parentClientId", parentClientId);
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private long sellOnCredit(long clientId, String amount) throws Exception {
        JsonNode shift = json(perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), creditEntry(clientId, amount))
                .andExpect(status().is2xxSuccessful()));
        JsonNode entries = shift.get("creditEntries");
        return entries.get(entries.size() - 1).get("id").asLong();
    }

    private Map<String, Object> creditEntry(long clientId, String amount) {
        return Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal(amount), "fuelType", "PETROL");
    }

    private ResultActions recordPayment(long clientId, String amount) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId),
                setup.manager(), paymentBody(amount));
    }

    private Map<String, Object> paymentBody(String amount) {
        return Map.of("amount", new BigDecimal(amount), "paymentMode", "CASH", "paidAt", LocalDate.now().toString());
    }

    private void setExpenseApprovalThreshold(String threshold) throws Exception {
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("expenseApprovalThreshold", new BigDecimal(threshold)))
                .andExpect(status().isOk());
    }

    private BigDecimal outstandingOf(long clientId) throws Exception {
        JsonNode summary = json(perform(get("/api/pumps/{pumpId}/credit-ledger", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        for (JsonNode client : summary) {
            if (client.get("id").asLong() == clientId) {
                return client.get("outstandingBalance").decimalValue();
            }
        }
        JsonNode clients = json(perform(get("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        for (JsonNode client : clients) {
            if (client.get("id").asLong() == clientId) {
                return client.get("outstandingBalance").decimalValue();
            }
        }
        throw new AssertionError("Client " + clientId + " missing from ledger");
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L);
    }
}
