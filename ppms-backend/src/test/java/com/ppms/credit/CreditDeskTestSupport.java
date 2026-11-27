package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A pump with an open shift and the calls the credit desk tests make; interest at 3%/month is ₹10 a day on ₹10,000. */
abstract class CreditDeskTestSupport extends ApiIntegrationTest {

    protected static final LocalDate TODAY = TestDates.today();

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected CreditInterestChargeRepository interestChargeRepository;

    protected PumpSetup setup;
    protected long shiftId;

    protected void openPumpAndShift() throws Exception {
        setup = createPumpWithStock("5000");
        shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    protected long createClient(Long parentId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", uniquePhone());
        body.put("parentClientId", parentId);
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    protected void chargeInterestAtThreePercent(long clientId) throws Exception {
        perform(patch("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/interest-settings", setup.pumpId(), clientId),
                setup.owner(), Map.of("monthlyInterestRate", new BigDecimal("3.00"), "interestGraceDays", 1))
                .andExpect(status().isOk());
    }

    protected void applyInterest(long clientId) throws Exception {
        perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/interest/apply", setup.pumpId(), clientId), setup.owner())
                .andExpect(status().is2xxSuccessful());
    }

    /** Sell first and back-date afterwards: an overdue account takes no new credit. */
    protected long sellOnCredit(long clientId, String amount) throws Exception {
        JsonNode shift = json(perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), Map.of("clientName", "Client", "clientId", clientId,
                        "amount", new BigDecimal(amount), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful()));
        JsonNode entries = shift.get("creditEntries");
        return entries.get(entries.size() - 1).get("id").asLong();
    }

    protected void backdate(long entryId, int daysAgo) {
        jdbcTemplate.update("UPDATE shift_credit_entries SET created_at = ?, sale_date = ? WHERE id = ?",
                TestDates.now().minusDays(daysAgo), TODAY.minusDays(daysAgo), entryId);
    }

    protected ResultActions pay(long clientId, String amount, boolean asAdvance) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId),
                setup.owner(), Map.of("amount", new BigDecimal(amount), "paymentMode", "CASH",
                        "paidAt", TODAY.toString(), "recordAsAdvance", asAdvance));
    }

    protected ResultActions desk(String path, long clientId, User user, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/" + path, setup.pumpId(), clientId), user, body);
    }

    protected ResultActions creditNote(long clientId, User user, String amount, String debitType, Long debitId)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("reason", "Short delivery");
        body.put("debitType", debitType);
        body.put("debitId", debitId);
        return desk("credit-notes", clientId, user, body);
    }

    protected JsonNode client(long clientId) throws Exception {
        JsonNode summary = json(perform(get("/api/pumps/{pumpId}/credit-ledger", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        for (JsonNode row : summary) {
            if (row.get("id").asLong() == clientId) {
                return row;
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the ledger");
    }

    protected BigDecimal outstanding(long clientId) throws Exception {
        return client(clientId).get("outstandingBalance").decimalValue();
    }

    protected BigDecimal liveInterest(long clientId) {
        return interestChargeRepository.sumAmountByClientId(clientId);
    }

    protected JsonNode agingRow(long clientId) throws Exception {
        JsonNode aging = json(perform(get("/api/pumps/{pumpId}/credit-ledger/aging", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        for (JsonNode row : aging.get("clients")) {
            if (row.get("clientId").asLong() == clientId) {
                return row;
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the aging");
    }

    protected JsonNode ledgerRows(long clientId) throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/transactions/all", setup.pumpId(), clientId),
                setup.manager()).andExpect(status().isOk()));
    }

    protected boolean isDueAReminder(long clientId) throws Exception {
        JsonNode due = json(perform(get("/api/pumps/{pumpId}/collections/reminders/due", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        for (JsonNode row : due) {
            if (row.get("clientId").asLong() == clientId) {
                return true;
            }
        }
        return false;
    }

    protected static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L);
    }
}
