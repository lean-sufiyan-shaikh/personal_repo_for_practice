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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Credit notes clear the debit they name before the oldest bills and lower the interest from their own date; a
 * disputed debit is owed but neither chased nor charged interest until it is accepted or credited.
 * The interest client owes ₹10,000 since 40 days ago at 3%/month: ₹10 a day, ₹400 charged up to today.
 */
class CreditNoteAndDisputeIntegrationTest extends CreditDeskTestSupport {

    @Autowired private AuditLogRepository auditLogRepository;

    private long clientId;
    private long saleId;

    @BeforeEach
    void createClientOwingAnOverdueSaleWithInterest() throws Exception {
        openPumpAndShift();
        clientId = createClient(null);
        chargeInterestAtThreePercent(clientId);
        saleId = sellOnCredit(clientId, "10000.00");
        backdate(saleId, 40);
        applyInterest(clientId);
    }

    @Test
    void creditNoteOnTheSaleLowersTheBalanceAndTheInterestFromItsOwnDate() throws Exception {
        assertThat(liveInterest(clientId)).isEqualByComparingTo("400.00");

        JsonNode note = json(creditNote(clientId, setup.owner(), "4000.00", "SALE", saleId)
                .andExpect(status().isCreated()));

        assertThat(note.get("kind").asText()).isEqualTo("CREDIT_NOTE");
        assertThat(note.get("gstCreditNoteId").isNull()).isTrue();
        // 39 days on ₹10,000 and today on ₹6,000
        assertThat(liveInterest(clientId)).isEqualByComparingTo("396.00");
        assertThat(outstanding(clientId)).isEqualByComparingTo("6396.00");
        assertThat(agingRow(clientId).get("daysOverdue").asLong()).isEqualTo(10);
        assertThat(auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()))
                .anyMatch(log -> log.getAction() == AuditAction.CREDIT_NOTE_ISSUED);
    }

    @Test
    void creditNoteNamingTheNewerSaleLeavesTheOldestBillOverdueWhileOneNamingNothingClearsIt() throws Exception {
        long otherClient = createClient(null);
        long newerSale = sellOnCredit(otherClient, "500.00");
        backdate(sellOnCredit(otherClient, "500.00"), 40);

        creditNote(otherClient, setup.admin(), "500.00", "SALE", newerSale).andExpect(status().isCreated());
        assertThat(agingRow(otherClient).get("daysOverdue").asLong()).isEqualTo(10);

        creditNote(otherClient, setup.admin(), "500.00", null, null).andExpect(status().isCreated());
        assertThat(agingRow(otherClient).get("daysOverdue").asLong()).isZero();
        assertThat(outstanding(otherClient)).isEqualByComparingTo("0.00");
    }

    @Test
    void creditNotesAreForOwnersAndAdminsWithinWhatIsOwedAndStopTheSaleBeingVoided() throws Exception {
        creditNote(clientId, setup.manager(), "100.00", "SALE", saleId).andExpect(status().isForbidden());
        creditNote(clientId, setup.owner(), "10000.01", "SALE", saleId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_NOTE_EXCEEDS_DEBIT"));
        creditNote(clientId, setup.owner(), "10400.01", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_NOTE_EXCEEDS_OUTSTANDING"));
        Map<String, Object> tooMuchReturned = new HashMap<>(Map.of("amount", new BigDecimal("10.00"),
                "reason", "Returned", "debitType", "SALE", "debitId", saleId, "returnedQuantity", new BigDecimal("999")));
        desk("credit-notes", clientId, setup.owner(), tooMuchReturned).andExpect(status().isBadRequest());

        creditNote(clientId, setup.owner(), "100.00", "SALE", saleId).andExpect(status().isCreated());
        perform(patch("/api/pumps/{pumpId}/shifts/{shiftId}/credit-entries/{entryId}/void", setup.pumpId(), shiftId,
                saleId), setup.manager(), Map.of("voidReason", "Wrong amount"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_SALE_HAS_CREDIT_NOTE"));
    }

    @Test
    void resendingACreditNoteWithTheSameKeyIssuesItOnce() throws Exception {
        Map<String, Object> body = Map.of("amount", new BigDecimal("250.00"), "reason", "Rate difference");
        String key = "note-" + shortId();
        long first = json(perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/credit-notes", setup.pumpId(), clientId)
                .header("Idempotency-Key", key), setup.owner(), body).andExpect(status().isCreated())).get("id").asLong();
        long replayed = json(perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/credit-notes", setup.pumpId(), clientId)
                .header("Idempotency-Key", key), setup.owner(), body)
                .andExpect(header().string("Idempotent-Replayed", "true"))).get("id").asLong();

        assertThat(replayed).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM credit_adjustments WHERE client_id = ?",
                Integer.class, clientId)).isOne();
    }

    @Test
    void disputedSaleIsNeitherChasedNorChargedUntilAcceptedThenEverythingComesBack() throws Exception {
        assertThat(isDueAReminder(clientId)).isTrue();

        JsonNode dispute = json(raise("SALE", saleId).andExpect(status().isCreated()));

        assertThat(liveInterest(clientId)).isEqualByComparingTo("0.00");
        assertThat(outstanding(clientId)).isEqualByComparingTo("10000.00");
        assertThat(agingRow(clientId).get("daysOverdue").asLong()).isZero();
        assertThat(isDueAReminder(clientId)).isFalse();
        assertThat(rowOf(ledgerRows(clientId), "SALE").get("openDisputeId").asLong()).isEqualTo(dispute.get("id").asLong());
        raise("SALE", saleId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_DISPUTE_ALREADY_OPEN"));

        resolve(dispute.get("id").asLong(), setup.manager(), "ACCEPTED", null).andExpect(status().isForbidden());
        resolve(dispute.get("id").asLong(), setup.owner(), "ACCEPTED", null).andExpect(status().isOk());

        assertThat(liveInterest(clientId)).isEqualByComparingTo("400.00");
        assertThat(agingRow(clientId).get("daysOverdue").asLong()).isEqualTo(10);
        assertThat(rowOf(ledgerRows(clientId), "SALE").get("openDisputeId").isNull()).isTrue();
    }

    @Test
    void creditedDisputeIssuesACreditNoteAndTheCreditedPartNeverBoreInterest() throws Exception {
        long disputeId = json(raise("SALE", saleId).andExpect(status().isCreated())).get("id").asLong();

        JsonNode resolved = json(resolve(disputeId, setup.admin(), "CREDITED", "4000.00").andExpect(status().isOk()));

        assertThat(resolved.get("status").asText()).isEqualTo("CREDITED");
        // 40 days on the ₹6,000 left
        assertThat(liveInterest(clientId)).isEqualByComparingTo("240.00");
        assertThat(outstanding(clientId)).isEqualByComparingTo("6240.00");
        JsonNode note = rowOf(ledgerRows(clientId), "CREDIT_NOTE");
        assertThat(note.get("linkedReferenceId").asLong()).isEqualTo(saleId);
        resolve(disputeId, setup.owner(), "ACCEPTED", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_DISPUTE_NOT_OPEN"));
    }

    private ResultActions raise(String debitType, long debitId) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/disputes", setup.pumpId()), setup.manager(),
                Map.of("debitType", debitType, "debitId", debitId, "reason", "Client says the bill is wrong"));
    }

    private ResultActions resolve(long disputeId, User user, String resolution, String creditAmount)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("resolution", resolution);
        body.put("note", "Checked the meter slip");
        body.put("creditAmount", creditAmount == null ? null : new BigDecimal(creditAmount));
        return perform(post("/api/pumps/{pumpId}/credit-ledger/disputes/{id}/resolve", setup.pumpId(), disputeId),
                user, body);
    }

    private static JsonNode rowOf(JsonNode ledger, String type) {
        for (JsonNode row : ledger) {
            if (row.get("type").asText().equals(type)) {
                return row;
            }
        }
        throw new AssertionError("No " + type + " row");
    }
}
