package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditLogRepository;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writing off a bad debt stops interest and chasing and shows in the P&amp;L; closing needs nothing left on the account;
 * merging a duplicate moves everything to the client it duplicates and recharges interest on the merged balance.
 * The interest client owes ₹10,000 since 40 days ago at 3%/month (₹10 a day, ₹400 charged up to today).
 */
class CreditWriteOffCloseMergeIntegrationTest extends CreditDeskTestSupport {

    @Autowired private AuditLogRepository auditLogRepository;

    private long clientId;

    @BeforeEach
    void createClientOwingAnOverdueSaleWithInterest() throws Exception {
        openPumpAndShift();
        clientId = createClient(null);
        chargeInterestAtThreePercent(clientId);
        backdate(sellOnCredit(clientId, "10000.00"), 40);
        applyInterest(clientId);
    }

    @Test
    void writeOffStopsInterestAndChasingFromItsDateAndShowsInTheProfitAndLoss() throws Exception {
        writeOff(setup.admin(), "4000.00").andExpect(status().isForbidden());
        writeOff(setup.owner(), "10400.01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_WRITE_OFF_EXCEEDS_OUTSTANDING"));

        writeOff(setup.owner(), "4000.00").andExpect(status().isCreated());

        // no interest from today: 39 days on ₹10,000
        assertThat(liveInterest(clientId)).isEqualByComparingTo("390.00");
        assertThat(outstanding(clientId)).isEqualByComparingTo("6390.00");
        assertThat(client(clientId).get("writtenOffOn").asText()).isEqualTo(TODAY.toString());
        assertThat(agingRow(clientId).get("daysOverdue").asLong()).isZero();
        assertThat(isDueAReminder(clientId)).isFalse();
        applyInterest(clientId);
        assertThat(liveInterest(clientId)).isEqualByComparingTo("390.00");
        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.operator(),
                Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal("10.00"), "fuelType", "PETROL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_CLIENT_WRITTEN_OFF"));
        JsonNode profitAndLoss = json(perform(get("/api/pumps/{pumpId}/reports/profit-loss", setup.pumpId())
                .param("from", TODAY.toString()).param("to", TODAY.toString()), setup.owner()).andExpect(status().isOk()));
        assertThat(profitAndLoss.get("badDebtWrittenOff").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(profitAndLoss.get("netOperatingProfit").decimalValue())
                .isEqualByComparingTo(profitAndLoss.get("grossProfit").decimalValue()
                        .subtract(profitAndLoss.get("approvedExpenses").decimalValue())
                        .subtract(profitAndLoss.get("bankCharges").decimalValue())
                        .subtract(profitAndLoss.get("payrollPaid").decimalValue())
                        .subtract(new BigDecimal("4000.00")));
    }

    @Test
    void closingNeedsNothingLeftDisablesTheSubAccountsAndOnlyTheOwnerReopens() throws Exception {
        long parentId = createClient(null);
        long subId = createClient(parentId);
        sellOnCredit(subId, "500.00");

        close(parentId, setup.owner()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_CLOSE_HAS_BALANCE"));
        pay(subId, "500.00", false).andExpect(status().isCreated());
        close(parentId, setup.admin()).andExpect(status().isForbidden());
        close(parentId, setup.owner()).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForList("SELECT is_active FROM credit_clients WHERE id IN (?, ?)", Boolean.class,
                parentId, subId)).containsOnly(false);
        assertThat(client(parentId).get("closedAt").isNull()).isFalse();
        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.operator(),
                Map.of("clientName", "Client", "clientId", subId, "amount", new BigDecimal("10.00"), "fuelType", "PETROL"))
                .andExpect(status().isBadRequest());

        perform(post("/api/pumps/{pumpId}/credit-ledger/clients/{id}/reopen", setup.pumpId(), parentId), setup.admin())
                .andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/credit-ledger/clients/{id}/reopen", setup.pumpId(), parentId), setup.owner())
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForList("SELECT is_active FROM credit_clients WHERE id IN (?, ?)", Boolean.class,
                parentId, subId)).containsOnly(true);
    }

    @Test
    void mergeMovesEveryRecordClosesTheDuplicateAndRechargesInterestOnTheMergedBalance() throws Exception {
        long duplicateId = createClient(null);
        backdate(sellOnCredit(duplicateId, "5000.00"), 40);
        pay(duplicateId, "1000.00", false).andExpect(status().isCreated());
        desk("deposits", duplicateId, setup.manager(), Map.of("amount", new BigDecimal("200.00"), "paymentMode", "UPI",
                "referenceNo", "UTR9")).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE credit_clients SET portal_pin_hash = 'hash' WHERE id = ?", duplicateId);

        merge(duplicateId, clientId, setup.admin()).andExpect(status().isForbidden());
        merge(duplicateId, clientId, setup.owner()).andExpect(status().isOk());

        // 39 days on ₹15,000 and today on ₹14,000 after the duplicate's payment
        assertThat(liveInterest(clientId)).isEqualByComparingTo("599.00");
        assertThat(outstanding(clientId)).isEqualByComparingTo("14599.00");
        assertThat(client(clientId).get("depositHeld").decimalValue()).isEqualByComparingTo("200.00");
        JsonNode duplicate = client(duplicateId);
        assertThat(duplicate.get("mergedIntoClientId").asLong()).isEqualTo(clientId);
        assertThat(duplicate.get("outstandingBalance").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT portal_pin_hash IS NULL AND NOT is_active FROM credit_clients"
                + " WHERE id = ?", Boolean.class, duplicateId)).isTrue();
        assertThat(auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()))
                .anyMatch(log -> log.getAction() == AuditAction.CREDIT_CLIENTS_MERGED);
    }

    @Test
    void mergeRefusesAMainAccountIntoASubAccountAndAClientIntoItself() throws Exception {
        long subId = createClient(clientId);
        long otherMain = createClient(null);

        merge(otherMain, subId, setup.owner()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_MERGE_INCOMPATIBLE"));
        merge(otherMain, otherMain, setup.owner()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_MERGE_SAME_CLIENT"));
    }

    private ResultActions writeOff(User user, String amount) throws Exception {
        return desk("write-off", clientId, user, Map.of("amount", new BigDecimal(amount), "reason", "Client vanished"));
    }

    private ResultActions close(long id, User user) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/clients/{id}/close", setup.pumpId(), id), user,
                Map.of("reason", "Stopped buying"));
    }

    private ResultActions merge(long sourceId, long targetId, User user) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/clients/{id}/merge-into/{targetId}", setup.pumpId(),
                sourceId, targetId), user);
    }
}
