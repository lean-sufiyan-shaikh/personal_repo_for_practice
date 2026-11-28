package com.ppms.credit;

import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** References, cheques, possible duplicates, resends and the back-dated rule when a credit payment is recorded. */
class CreditPaymentRecordingIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private long clientId;

    @BeforeEach
    void createClientWhoOwes() throws Exception {
        setup = createPumpWithStock("5000");
        long shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        Map<String, Object> client = new HashMap<>();
        client.put("name", "Client " + shortId());
        client.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        clientId = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), client)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.operator(),
                Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal("50000.00"), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void referenceOlderThanNinetyDaysOrOnARejectedPaymentIsNotADuplicate() throws Exception {
        long oldId = json(record(setup.owner(), payment("500.00", "UPI", TODAY, "UTR-OLD")).andExpect(status().isCreated()))
                .get("id").asLong();
        jdbcTemplate.update("UPDATE credit_payments SET payment_date = ? WHERE id = ?", TODAY.minusDays(91), oldId);
        record(setup.manager(), payment("500.00", "UPI", TODAY, "UTR-OLD")).andExpect(status().isCreated());

        long rejectedId = json(record(setup.manager(), payment("500.00", "UPI", TODAY.minusDays(30), "UTR-REJ"))
                .andExpect(status().isCreated())).get("id").asLong();
        decide(rejectedId, "REJECTED", null).andExpect(status().isOk());
        record(setup.manager(), payment("500.00", "UPI", TODAY, "UTR-REJ")).andExpect(status().isCreated());
    }

    @Test
    void resendWithTheSameIdempotencyKeyRecordsAndApprovesOnce() throws Exception {
        String recordKey = "record-" + shortId();
        Map<String, Object> body = payment("700.00", "CASH", TODAY.minusDays(10), null);
        long firstId = json(recordWithKey(recordKey, body).andExpect(status().isCreated())).get("id").asLong();
        long replayedId = json(recordWithKey(recordKey, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))).get("id").asLong();
        assertThat(replayedId).isEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM credit_payments WHERE client_id = ?",
                Integer.class, clientId)).isOne();

        String approveKey = "approve-" + shortId();
        approveWithKey(firstId, approveKey).andExpect(status().isOk());
        approveWithKey(firstId, approveKey)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.paymentApprovalStatus").value("APPROVED"));
    }

    private ResultActions record(User user, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId), user, body);
    }

    private ResultActions recordWithKey(String key, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId)
                .header("Idempotency-Key", key), setup.manager(), body);
    }

    private ResultActions decide(long paymentId, String status, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("reason", reason);
        return perform(patch("/api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/approve", setup.pumpId(), paymentId),
                setup.owner(), body);
    }

    private ResultActions approveWithKey(long paymentId, String key) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/approve", setup.pumpId(), paymentId)
                .header("Idempotency-Key", key), setup.owner(), Map.of("status", "APPROVED"));
    }

    private static Map<String, Object> payment(String amount, String mode, LocalDate paidAt, String referenceNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("paymentMode", mode);
        body.put("paidAt", paidAt.toString());
        body.put("referenceNo", referenceNo);
        return body;
    }
}
