package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditLogRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reversing an approved payment puts the account back as if it had never been paid: balance, interest (3%/month, so
 * ₹10,000 owed for a day costs ₹10.00) and the overdue days, while the day it was paid keeps its figures.
 */
class CreditPaymentReversalIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();
    private static final int SALE_DAYS_AGO = 40;
    private static final int PAYMENT_DAYS_AGO = 20;

    @Autowired private CreditInterestChargeRepository interestChargeRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private CreditPaymentRepository paymentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private long shiftId;
    private long clientId;

    @BeforeEach
    void createClientWhoPaidAnOverdueSale() throws Exception {
        setup = createPumpWithStock("5000");
        shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        clientId = createClient();
        perform(patch("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/interest-settings", setup.pumpId(), clientId),
                setup.owner(), Map.of("monthlyInterestRate", new BigDecimal("3.00"), "interestGraceDays", 1))
                .andExpect(status().isOk());
        backdateSale(sellOnCredit("10000.00"), SALE_DAYS_AGO);
        perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/interest/apply", setup.pumpId(), clientId), setup.owner())
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void reversingAPaymentRestoresTheBalanceTheInterestAndTheOverdueDays() throws Exception {
        long paymentId = recordCheque("10000.00", TODAY.minusDays(PAYMENT_DAYS_AGO));
        // 19 days on ₹10,000 before the payment, nothing after it
        assertThat(liveInterest()).isEqualByComparingTo("190.00");
        assertThat(outstanding()).isEqualByComparingTo("190.00");
        assertThat(agingRow().get("daysOverdue").asLong()).isZero();

        reverse(paymentId, setup.owner(), Map.of("reason", "Cheque bounced")).andExpect(status().isCreated());

        assertThat(liveInterest()).isEqualByComparingTo("400.00");
        assertThat(outstanding()).isEqualByComparingTo("10400.00");
        assertThat(agingRow().get("daysOverdue").asLong()).isEqualTo(10);
    }

    @Test
    void reversalIsDatedTodayWithItsBounceChargeAsASeparateDebitAndThePaymentIsKeptAsReversed() throws Exception {
        long paymentId = recordCheque("4000.00", TODAY.minusDays(PAYMENT_DAYS_AGO));
        BigDecimal before = outstanding();

        JsonNode reversal = json(reverse(paymentId, setup.admin(),
                Map.of("reason", "Cheque bounced", "bounceCharge", new BigDecimal("250")))
                .andExpect(status().isCreated()));

        assertThat(reversal.get("reversalDate").asText()).isEqualTo(TODAY.toString());
        assertThat(reversal.get("bounceCharge").decimalValue()).isEqualByComparingTo("250.00");
        // ₹4,000 back on the account, the interest it saved (21 days on ₹4,000 = ₹84.00) and the bounce charge
        assertThat(outstanding().subtract(before)).isEqualByComparingTo("4334.00");
        JsonNode ledger = json(perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/transactions/all",
                setup.pumpId(), clientId), setup.manager()).andExpect(status().isOk()));
        assertThat(rowOfType(ledger, "PAYMENT").get("status").asText()).isEqualTo("REVERSED");
        assertThat(rowOfType(ledger, "PAYMENT").get("linkedReferenceId").asLong()).isEqualTo(reversal.get("id").asLong());
        assertThat(rowOfType(ledger, "PAYMENT_REVERSAL").get("businessDate").asText()).isEqualTo(TODAY.toString());
        assertThat(rowOfType(ledger, "PAYMENT_REVERSAL").get("linkedReferenceId").asLong()).isEqualTo(paymentId);
        assertThat(rowOfType(ledger, "BOUNCE_CHARGE").get("amount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(ledger.get(0).get("runningBalance").decimalValue()).isEqualByComparingTo(outstanding());
    }

    @Test
    void reversalIsAuditedAndNotifiesTheManagers() throws Exception {
        long paymentId = recordCheque("4000.00", TODAY);

        reverse(paymentId, setup.owner(), Map.of("reason", "Cheque bounced")).andExpect(status().isCreated());

        assertThat(auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()))
                .filteredOn(log -> log.getAction() == AuditAction.CREDIT_PAYMENT_REVERSED)
                .singleElement()
                .satisfies(log -> assertThat(log.getDescription()).contains("₹4000.00").contains("reason=Cheque bounced"));
        JsonNode managersBell = json(perform(get("/api/pumps/{pumpId}/notifications", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        assertThat(managersBell.toString()).contains("CREDIT_PAYMENT_REVERSED");
    }

    @Test
    void paymentCannotBeReversedTwiceNorByAManagerNorWithoutAReason() throws Exception {
        long paymentId = recordCheque("4000.00", TODAY);

        reverse(paymentId, setup.manager(), Map.of("reason", "Cheque bounced")).andExpect(status().isForbidden());
        reverse(paymentId, setup.owner(), Map.of("reason", " ")).andExpect(status().isBadRequest());
        reverse(paymentId, setup.owner(), Map.of("reason", "Cheque bounced")).andExpect(status().isCreated());
        reverse(paymentId, setup.admin(), Map.of("reason", "Again"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_ALREADY_REVERSED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM credit_payment_reversals WHERE payment_id = ?",
                Integer.class, paymentId)).isOne();
    }

    @Test
    void pendingAndBankMatchedPaymentsAreNotReversed() throws Exception {
        long pendingId = json(perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId),
                setup.manager(), Map.of("amount", new BigDecimal("500.00"), "paymentMode", "CASH",
                        "paidAt", TODAY.minusDays(30).toString()))
                .andExpect(status().isCreated())).get("id").asLong();
        reverse(pendingId, setup.owner(), Map.of("reason", "Wrong"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_NOT_REVERSIBLE"));

        long matchedId = recordCheque("4000.00", TODAY);
        long importId = jdbcTemplate.queryForObject("INSERT INTO bank_statement_imports (pump_id, bank_name,"
                + " statement_from_date, statement_to_date, imported_by_user_id) VALUES (?, 'SBI', ?, ?, ?) RETURNING id",
                Long.class, setup.pumpId(), TODAY, TODAY, setup.owner().getId());
        jdbcTemplate.update("INSERT INTO bank_statement_lines (import_id, txn_date, credit_amount, match_status, matched_payment_id)"
                + " VALUES (?, ?, 4000.00, 'MATCHED', ?)", importId, TODAY, matchedId);
        reverse(matchedId, setup.owner(), Map.of("reason", "Cheque bounced"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_MATCHED_TO_BANK_LINE"));
    }

    @Test
    void reversalNeedsTodayOpenButLeavesTheClosedDayOfThePaymentAsItWas() throws Exception {
        LocalDate paidOn = TODAY.minusDays(PAYMENT_DAYS_AGO);
        long paymentId = recordCheque("4000.00", paidOn);
        BigDecimal recoveredOnPaymentDay = creditRecoveredOn(paidOn);
        closeDay(paidOn);
        closeDay(TODAY);

        reverse(paymentId, setup.owner(), Map.of("reason", "Cheque bounced"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"));

        jdbcTemplate.update("DELETE FROM day_closes WHERE pump_id = ? AND business_date = ?", setup.pumpId(), TODAY);
        reverse(paymentId, setup.owner(), Map.of("reason", "Cheque bounced")).andExpect(status().isCreated());
        assertThat(creditRecoveredOn(paidOn)).isEqualByComparingTo(recoveredOnPaymentDay);
        assertThat(creditRecoveredOn(TODAY)).isEqualByComparingTo("-4000.00");
    }

    @Test
    void resendWithTheSameIdempotencyKeyReturnsTheFirstReversal() throws Exception {
        long paymentId = recordCheque("4000.00", TODAY);
        String key = "reverse-" + shortId();
        Map<String, Object> body = Map.of("reason", "Cheque bounced", "bounceCharge", new BigDecimal("100.00"));

        long firstId = json(reverseWithKey(paymentId, key, body).andExpect(status().isCreated())).get("id").asLong();
        long replayedId = json(reverseWithKey(paymentId, key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))).get("id").asLong();

        assertThat(replayedId).isEqualTo(firstId);
    }

    private ResultActions reverse(long paymentId, User user, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/reverse", setup.pumpId(), paymentId),
                user, body);
    }

    private ResultActions reverseWithKey(long paymentId, String key, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/reverse", setup.pumpId(), paymentId)
                .header("Idempotency-Key", key), setup.owner(), body);
    }

    private long recordCheque(String amount, LocalDate paidAt) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId),
                setup.owner(), Map.of("amount", new BigDecimal(amount), "paymentMode", "CHEQUE",
                        "paidAt", paidAt.toString(), "referenceNo", "CHQ" + shortId(), "chequeBank", "SBI"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void closeDay(LocalDate date) {
        jdbcTemplate.update("INSERT INTO day_closes (pump_id, business_date, status, current_version, closed_by_user_id,"
                + " closed_at) VALUES (?, ?, 'CLOSED', 1, ?, NOW())", setup.pumpId(), date, setup.owner().getId());
    }

    private BigDecimal creditRecoveredOn(LocalDate date) {
        return paymentRepository.sumAmountByPumpIdAndDate(setup.pumpId(), date);
    }

    private long createClient() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private long sellOnCredit(String amount) throws Exception {
        JsonNode shift = json(perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), Map.of("clientName", "Client", "clientId", clientId,
                        "amount", new BigDecimal(amount), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful()));
        JsonNode entries = shift.get("creditEntries");
        return entries.get(entries.size() - 1).get("id").asLong();
    }

    private void backdateSale(long entryId, int daysAgo) {
        jdbcTemplate.update("UPDATE shift_credit_entries SET created_at = ?, sale_date = ? WHERE id = ?",
                TestDates.now().minusDays(daysAgo), TODAY.minusDays(daysAgo), entryId);
    }

    private BigDecimal liveInterest() {
        return interestChargeRepository.sumAmountByClientId(clientId);
    }

    private BigDecimal outstanding() throws Exception {
        JsonNode summary = json(perform(get("/api/pumps/{pumpId}/credit-ledger", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        for (JsonNode client : summary) {
            if (client.get("id").asLong() == clientId) {
                return client.get("outstandingBalance").decimalValue();
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the ledger");
    }

    private JsonNode agingRow() throws Exception {
        JsonNode aging = json(perform(get("/api/pumps/{pumpId}/credit-ledger/aging", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        for (JsonNode row : aging.get("clients")) {
            if (row.get("clientId").asLong() == clientId) {
                return row;
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the aging");
    }

    private static JsonNode rowOfType(JsonNode ledger, String type) {
        for (JsonNode row : ledger) {
            if (row.get("type").asText().equals(type)) {
                return row;
            }
        }
        throw new AssertionError("No " + type + " row in the ledger");
    }
}
