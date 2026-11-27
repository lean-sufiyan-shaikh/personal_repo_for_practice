package com.ppms.settlement;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A chargeback or refund lowers what reached the bank without touching the wallet, is a cost in the DAY sheet and the
 * profit and loss, and can be matched to one debit line of the bank statement.
 */
class ChargebackIntegrationTest extends ApiIntegrationTest {

    private final String today = TestDates.today().toString();

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private long settlementId;

    @BeforeEach
    void createPumpWithASettledCardDay() throws Exception {
        setup = createPumpWithStock("5000");
        closeShift();
        settlementId = json(perform(post("/api/pumps/{pumpId}/settlements", setup.pumpId()), setup.manager(),
                Map.of("paymentType", "CARD", "settlementDate", today, "amountReceived", new BigDecimal("600.00"),
                        "mdrAmount", BigDecimal.ZERO)).andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void chargebackLowersTheSettlementsNetButLeavesTheWalletAlone() throws Exception {
        BigDecimal pendingBefore = wallet().get("cardPending").decimalValue();

        JsonNode chargeback = json(record(setup.owner(), againstSettlement("150.00", "CB-" + shortId()))
                .andExpect(status().isCreated()));

        assertThat(chargeback.get("paymentType").asText()).isEqualTo("CARD");
        JsonNode settlement = json(perform(get("/api/pumps/{pumpId}/settlements", setup.pumpId()), setup.manager()))
                .get("content").get(0);
        assertThat(settlement.get("chargebacksAmount").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(settlement.get("netAfterChargebacks").decimalValue()).isEqualByComparingTo("450.00");
        assertThat(wallet().get("cardPending").decimalValue()).isEqualByComparingTo(pendingBefore);

        record(setup.owner(), againstSettlement("450.01", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGEBACK_EXCEEDS_SETTLEMENT"))
                .andExpect(jsonPath("$.params.remaining").value("450.00"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action::text = ?",
                Long.class, setup.pumpId(), "CHARGEBACK_RECORDED")).isEqualTo(1);
    }

    @Test
    void chargebackIsACostOfItsDayInTheDaySheetAndTheProfitAndLoss() throws Exception {
        record(setup.owner(), againstSettlement("100.00", null)).andExpect(status().isCreated());
        Map<String, Object> refund = new HashMap<>(Map.of("kind", "REFUND", "paymentType", "UPI", "chargebackDate", today,
                "amount", new BigDecimal("40.00"), "reason", "Customer paid twice", "collectionDate", today));
        record(setup.admin(), refund).andExpect(status().isCreated());

        JsonNode sheet = json(perform(post("/api/pumps/{pumpId}/balance-sheets/generate", setup.pumpId()), setup.manager(),
                Map.of("reportType", "DAY", "reportDate", today)).andExpect(status().is2xxSuccessful()));
        assertThat(sheet.get("totalChargebacksAmount").decimalValue()).isEqualByComparingTo("140.00");
        assertThat(sheet.get("settlementSummary").get("chargebacksOnDate").decimalValue()).isEqualByComparingTo("140.00");
        assertThat(sheet.get("settlementSummary").get("chargebackLines")).hasSize(2);
        assertThat(sheet.get("totalNetProfit").decimalValue()).isEqualByComparingTo(
                sheet.get("totalGrossProfit").decimalValue().add(sheet.get("totalDipNetAmount").decimalValue())
                        .subtract(new BigDecimal("140.00")));

        JsonNode report = json(perform(get("/api/pumps/{pumpId}/reports/profit-loss", setup.pumpId())
                .param("from", today).param("to", today), setup.manager()).andExpect(status().isOk()));
        assertThat(report.get("chargebacks").decimalValue()).isEqualByComparingTo("140.00");
        assertThat(report.get("netOperatingProfit").decimalValue()).isEqualByComparingTo(
                report.get("grossProfit").decimalValue().subtract(new BigDecimal("140.00")));
    }

    @Test
    void chargebackMatchesOneDebitLineOnlyAndNeverACreditLine() throws Exception {
        long chargebackId = json(record(setup.owner(), againstSettlement("150.00", null)).andExpect(status().isCreated()))
                .get("id").asLong();
        long importId = jdbcTemplate.queryForObject("""
                INSERT INTO bank_statement_imports (pump_id, bank_name, statement_from_date, statement_to_date,
                                                    imported_by_user_id)
                VALUES (?, 'SBI', CURRENT_DATE, CURRENT_DATE, ?) RETURNING id
                """, Long.class, setup.pumpId(), setup.owner().getId());
        long firstDebit = line(importId, "150.00", "0");
        long secondDebit = line(importId, "150.00", "0");
        long credit = line(importId, "0", "150.00");

        JsonNode candidates = json(perform(get("/api/pumps/{pumpId}/bank-reconciliation/lines/{lineId}/candidates",
                setup.pumpId(), firstDebit), setup.owner()).andExpect(status().isOk()));
        assertThat(candidates.findValuesAsText("type")).contains("CHARGEBACK");

        matchLine(credit, chargebackId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_LINE_DIRECTION_MISMATCH"));
        matchLine(firstDebit, chargebackId).andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedChargebackId").value(chargebackId));
        matchLine(secondDebit, chargebackId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_LINE_RECORD_ALREADY_MATCHED"));
    }

    @Test
    void recordingNeedsALinkAUniqueReferenceAndAnOwnerOrAdmin() throws Exception {
        String reference = "CB-" + shortId();
        record(setup.manager(), againstSettlement("10.00", null)).andExpect(status().isForbidden());
        record(setup.owner(), Map.of("kind", "CHARGEBACK", "chargebackDate", today, "amount", new BigDecimal("10.00"),
                "reason", "Disputed")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGEBACK_LINK_REQUIRED"));
        record(setup.owner(), againstSettlement("10.00", reference)).andExpect(status().isCreated());
        record(setup.owner(), againstSettlement("10.00", reference.toLowerCase())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGEBACK_REFERENCE_DUPLICATE"));
        Map<String, Object> future = againstSettlement("10.00", null);
        future.put("chargebackDate", TestDates.today().plusDays(1).toString());
        record(setup.owner(), future).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARGEBACK_DATE_IN_FUTURE"));
        perform(get("/api/pumps/{pumpId}/chargebacks", setup.pumpId()), setup.manager()).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        perform(get("/api/pumps/{pumpId}/chargebacks", setup.pumpId()), setup.operator()).andExpect(status().isForbidden());
    }

    private Map<String, Object> againstSettlement(String amount, String reference) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "CHARGEBACK");
        body.put("chargebackDate", today);
        body.put("amount", new BigDecimal(amount));
        body.put("reason", "Card holder disputed the payment");
        body.put("settlementId", settlementId);
        if (reference != null) {
            body.put("reference", reference);
        }
        return body;
    }

    private ResultActions record(User user, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/chargebacks", setup.pumpId()), user, body);
    }

    private long line(long importId, String debit, String credit) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO bank_statement_lines (import_id, txn_date, narration, debit_amount, credit_amount)
                VALUES (?, CURRENT_DATE, 'Card chargeback', ?, ?) RETURNING id
                """, Long.class, importId, new BigDecimal(debit), new BigDecimal(credit));
    }

    private ResultActions matchLine(long lineId, long chargebackId) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/bank-reconciliation/lines/{lineId}", setup.pumpId(), lineId),
                setup.owner(), Map.of("status", "MATCHED", "matchedChargebackId", chargebackId));
    }

    private JsonNode wallet() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/settlements/wallet", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
    }

    private void closeShift() throws Exception {
        long shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        Map<String, Object> close = new HashMap<>();
        close.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        close.put("cashCollected", new BigDecimal("400.00"));
        close.put("upiCollected", BigDecimal.ZERO);
        close.put("cardCollected", new BigDecimal("600.00"));
        close.put("fleetCardCollected", BigDecimal.ZERO);
        close.put("creditTotal", BigDecimal.ZERO);
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), close)
                .andExpect(status().is2xxSuccessful());
    }
}
