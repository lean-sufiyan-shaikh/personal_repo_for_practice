package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
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
    void upiBankTransferAndChequeNeedAReferenceButCashDoesNot() throws Exception {
        for (String mode : List.of("UPI", "BANK_TRANSFER", "CHEQUE")) {
            record(setup.manager(), payment("100.00", mode, TODAY, " "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_REFERENCE_REQUIRED"))
                    .andExpect(jsonPath("$.params.modeCode").value(mode));
        }
        record(setup.manager(), payment("100.00", "CASH", TODAY, null)).andExpect(status().isCreated());

        Map<String, Object> cashWithChequeDate = payment("100.00", "CASH", TODAY, null);
        cashWithChequeDate.put("chequeDate", TODAY.toString());
        record(setup.manager(), cashWithChequeDate)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_CHEQUE_DETAILS_NOT_CHEQUE"));
    }

    @Test
    void sameReferenceWithinNinetyDaysIsAPossibleDuplicateUntilConfirmed() throws Exception {
        record(setup.manager(), payment("500.00", "UPI", TODAY.minusDays(3), "utr-777")).andExpect(status().isCreated());

        record(setup.manager(), payment("500.00", "BANK_TRANSFER", TODAY, " UTR-777 "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_POSSIBLE_DUPLICATE"))
                .andExpect(jsonPath("$.params.referenceNo").value("UTR-777"))
                .andExpect(jsonPath("$.params.paidAt").value(TODAY.minusDays(3).toString()));

        Map<String, Object> confirmed = payment("500.00", "BANK_TRANSFER", TODAY, " UTR-777 ");
        confirmed.put("confirmDuplicate", true);
        JsonNode saved = json(record(setup.manager(), confirmed).andExpect(status().isCreated()));
        assertThat(saved.get("referenceNo").asText()).isEqualTo("UTR-777");
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
    void postDatedChequeWaitsUntilItsDateEvenForTheOwnerWhoMayThenApproveIt() throws Exception {
        Map<String, Object> cheque = payment("2000.00", "CHEQUE", TODAY, "000123");
        cheque.put("chequeBank", "HDFC");
        cheque.put("chequeDate", TODAY.plusDays(5).toString());
        JsonNode saved = json(record(setup.owner(), cheque).andExpect(status().isCreated()));
        long paymentId = saved.get("id").asLong();
        assertThat(saved.get("paymentApprovalStatus").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(saved.get("chequeBank").asText()).isEqualTo("HDFC");

        decide(paymentId, "APPROVED", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_CHEQUE_NOT_DUE"));

        jdbcTemplate.update("UPDATE credit_payments SET payment_date = ?, cheque_date = ? WHERE id = ?",
                TODAY.minusDays(5), TODAY, paymentId);
        decide(paymentId, "APPROVED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentApprovalStatus").value("APPROVED"));
    }

    @Test
    void managersBackDatedPaymentWaitsForApprovalByThePumpsLimit() throws Exception {
        assertThat(statusOf(record(setup.manager(), payment("100.00", "CASH", TODAY.minusDays(7), null)))).isEqualTo("APPROVED");
        assertThat(statusOf(record(setup.manager(), payment("100.00", "CASH", TODAY.minusDays(8), null)))).isEqualTo("PENDING_APPROVAL");
        assertThat(statusOf(record(setup.owner(), payment("100.00", "CASH", TODAY.minusDays(30), null)))).isEqualTo("APPROVED");

        saveSettings(null, 30).andExpect(status().isOk());
        assertThat(statusOf(record(setup.manager(), payment("100.00", "CASH", TODAY.minusDays(8), null)))).isEqualTo("APPROVED");
    }

    @Test
    void creditPaymentThresholdIsItsOwnSettingNotTheExpenseThreshold() throws Exception {
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("expenseApprovalThreshold", new BigDecimal("100.00"))).andExpect(status().isOk());
        assertThat(statusOf(record(setup.manager(), payment("5000.00", "CASH", TODAY, null)))).isEqualTo("APPROVED");

        JsonNode settings = json(saveSettings(new BigDecimal("1000.00"), 7).andExpect(status().isOk()));
        assertThat(settings.get("creditPaymentApprovalThreshold").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(statusOf(record(setup.manager(), payment("1000.01", "CASH", TODAY, null)))).isEqualTo("PENDING_APPROVAL");

        saveSettings(new BigDecimal("1000.00"), 366)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BACKDATED_PAYMENT_DAYS_OUT_OF_RANGE"));
        saveSettings(new BigDecimal("0"), 7)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_THRESHOLD_INVALID"));
        JsonNode read = json(perform(get("/api/pumps/{pumpId}/collection-settings", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        assertThat(read.get("backdatedPaymentApprovalDays").asInt()).isEqualTo(7);
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

    private ResultActions saveSettings(BigDecimal threshold, int backdatedDays) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reminderStageDays", List.of(7, 15, 30));
        body.put("creditHoldDays", 45);
        body.put("creditPaymentApprovalThreshold", threshold);
        body.put("backdatedPaymentApprovalDays", backdatedDays);
        return perform(put("/api/pumps/{pumpId}/collection-settings", setup.pumpId()), setup.owner(), body);
    }

    private String statusOf(ResultActions result) throws Exception {
        return json(result.andExpect(status().isCreated())).get("paymentApprovalStatus").asText();
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
