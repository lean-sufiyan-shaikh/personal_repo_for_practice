package com.ppms.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BankReconciliationIntegrationTest extends ApiIntegrationTest {

    private static final String HEADER = "Date,Narration,Debit,Credit,Balance,UTR\n";

    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private String today;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("1000");
        today = TestDates.today().toString();
    }

    @Test
    void theSameStatementFileCannotBeImportedTwice() throws Exception {
        String csv = HEADER + today + ",UPI credit,0,500.00,500.00,UTR-" + shortId() + "\n";

        importStatement(csv).andExpect(status().isCreated());
        importStatement(csv)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already imported")));

        assertThat(json(perform(get("/api/pumps/{pumpId}/bank-reconciliation/imports", setup.pumpId()), setup.owner())))
                .hasSize(1);
    }

    @Test
    void anOverlappingStatementImportsOnlyItsNewLinesAndReportsTheSkippedOnes() throws Exception {
        String repeatedUtr = "UTR-" + shortId();
        String lineWithoutUtr = today + ",Cash deposit,0,300.00,800.00,\n";
        importStatement(HEADER + today + ",UPI credit,0,500.00,500.00," + repeatedUtr + "\n" + lineWithoutUtr)
                .andExpect(status().isCreated());

        importStatement(HEADER + today + ",UPI credit,0,500.00,500.00," + repeatedUtr + "\n" + lineWithoutUtr
                + today + ",UPI credit,0,200.00,1000.00,UTR-" + shortId() + "\n" + "not-a-date,Bad,0,1,1,\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalLines").value(1))
                .andExpect(jsonPath("$.skippedDuplicateLines").value(2))
                .andExpect(jsonPath("$.skippedInvalidRows").value(1));

        importStatement(HEADER + lineWithoutUtr)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_STATEMENT_NOTHING_NEW"));
    }

    @Test
    void theDateOrderIsChosenPerImportOrWorkedOutFromTheWholeFile() throws Exception {
        LocalDate fifthApril = LocalDate.of(TestDates.today().getYear(), 4, 5);
        String ambiguous = fifthApril.format(DateTimeFormatter.ofPattern("MM/dd/yyyy"));

        long monthFirst = json(importStatement(HEADER + ambiguous + ",A,0,10.00,10.00,\n", "MONTH_FIRST")
                .andExpect(status().isCreated())).get("id").asLong();
        assertThat(lines(monthFirst).get(0).get("txnDate").asText()).isEqualTo(fifthApril.toString());

        long detected = json(importStatement(HEADER + ambiguous + ",B,0,20.00,30.00,\n"
                + "04/25/" + fifthApril.getYear() + ",C,0,30.00,60.00,\n", "AUTO")
                .andExpect(status().isCreated())).get("id").asLong();
        assertThat(lines(detected).get(0).get("txnDate").asText()).isEqualTo(fifthApril.toString());

        importStatement(HEADER + "25/04/2026,D,0,1.00,1.00,\n04/25/2026,E,0,2.00,3.00,\n", "AUTO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_STATEMENT_DATE_FORMAT_AMBIGUOUS"));
    }

    @Test
    void aSettlementIsMatchedByItsUtrAndCannotThenBeVoidedUntilTheLineIsUnmatched() throws Exception {
        closeShiftWithUpi("600.00");
        String utr = "UTR" + shortId();
        long settlementId = recordUpiSettlement("600.00", utr);

        JsonNode summary = json(importStatement(HEADER + today + ",UPI SETTLEMENT " + utr + ",0,600.00,600.00," + utr + "\n")
                .andExpect(status().isCreated()));
        assertThat(summary.get("autoMatchedLines").asInt()).isEqualTo(1);
        assertThat(summary.get("matchedLines").asInt()).isEqualTo(1);
        JsonNode line = lines(summary.get("id").asLong()).get(0);
        assertThat(line.get("matchStatus").asText()).isEqualTo("MATCHED");
        assertThat(line.get("matchedSettlementId").asLong()).isEqualTo(settlementId);
        assertThat(line.get("matchedRecord").get("type").asText()).isEqualTo("SETTLEMENT");

        voidSettlement(settlementId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_MATCHED_TO_BANK_LINE"));
        updateLine(line.get("id").asLong(), Map.of("status", "UNMATCHED")).andExpect(status().isOk());
        voidSettlement(settlementId).andExpect(status().isOk());
    }

    @Test
    void aSettlementOfTheSameNetAmountIsSuggestedAndAcceptingItMatchesTheLineWithAnAuditEntry() throws Exception {
        closeShiftWithUpi("600.00");
        long settlementId = recordUpiSettlement("600.00", null);

        JsonNode summary = json(importStatement(HEADER + today + ",UPI SETTLEMENT,0,599.50,599.50,\n")
                .andExpect(status().isCreated()));
        assertThat(summary.get("autoSuggestedLines").asInt()).isEqualTo(1);
        assertThat(summary.get("suggestedLines").asInt()).isEqualTo(1);
        assertThat(summary.get("unmatchedCreditAmount").decimalValue()).isEqualByComparingTo("599.50");
        long lineId = lines(summary.get("id").asLong()).get(0).get("id").asLong();

        accept(lineId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.matchedSettlementId").value(settlementId))
                .andExpect(jsonPath("$.matchNotes").value(containsString("0.50")));
        assertThat(auditCount("BANK_LINE_MATCHED")).isEqualTo(1);

        accept(lineId).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BANK_LINE_NO_SUGGESTION"));
    }

    @Test
    void aCashDepositIsMatchedByItsSlipReferenceInTheNarration() throws Exception {
        String slip = "SLIP" + shortId();
        long depositId = recordDeposit("700.00", slip);

        JsonNode summary = json(importStatement(HEADER + today + ",CASH DEP " + slip + ",0,700.00,700.00,\n")
                .andExpect(status().isCreated()));

        JsonNode line = lines(summary.get("id").asLong()).get(0);
        assertThat(line.get("matchStatus").asText()).isEqualTo("MATCHED");
        assertThat(line.get("matchedDepositId").asLong()).isEqualTo(depositId);
    }

    @Test
    void aSupplierPaymentIsSuggestedForADebitLineAndCannotBeMatchedToACreditLine() throws Exception {
        long paymentId = recordSupplierPayment("50000.00");

        JsonNode summary = json(importStatement(HEADER
                + today + ",NEFT TO IOCL,50000.00,0,10000.00,\n"
                + today + ",REFUND,0,50000.00,60000.00,\n")
                .andExpect(status().isCreated()));
        JsonNode lines = lines(summary.get("id").asLong());
        JsonNode debitLine = lines.get(0);
        assertThat(debitLine.get("matchStatus").asText()).isEqualTo("SUGGESTED");
        assertThat(debitLine.get("matchedSupplierPaymentId").asLong()).isEqualTo(paymentId);
        assertThat(lines.get(1).get("matchStatus").asText()).isEqualTo("UNMATCHED");

        updateLine(lines.get(1).get("id").asLong(), Map.of("status", "MATCHED", "matchedSupplierPaymentId", paymentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_LINE_DIRECTION_MISMATCH"));
    }

    @Test
    void aRecordIsMatchedToOneLineOnlyAndAManualMatchTakesOverASuggestion() throws Exception {
        closeShiftWithUpi("600.00");
        long settlementId = recordUpiSettlement("600.00", null);
        long importId = json(importStatement(HEADER
                + today + ",UPI A,0,600.00,600.00,\n"
                + today + ",UPI B,0,600.00,1200.00,\n")
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode lines = lines(importId);
        long suggestedLine = lines.get(0).get("id").asLong();
        long otherLine = lines.get(1).get("id").asLong();
        assertThat(lines.get(0).get("matchStatus").asText()).isEqualTo("SUGGESTED");
        assertThat(lines.get(1).get("matchStatus").asText()).isEqualTo("UNMATCHED");

        JsonNode candidates = json(perform(get("/api/pumps/{pumpId}/bank-reconciliation/lines/{lineId}/candidates",
                setup.pumpId(), otherLine).param("search", "600"), setup.owner()).andExpect(status().isOk()));
        assertThat(candidates.findValuesAsText("type")).contains("SETTLEMENT");

        updateLine(otherLine, Map.of("status", "MATCHED", "matchedSettlementId", settlementId)).andExpect(status().isOk());
        assertThat(lines(importId).get(0).get("matchStatus").asText()).isEqualTo("UNMATCHED");
        updateLine(suggestedLine, Map.of("status", "MATCHED", "matchedSettlementId", settlementId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_LINE_RECORD_ALREADY_MATCHED"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bank_statement_lines SET match_status = 'MATCHED', matched_settlement_id = ? WHERE id = ?",
                settlementId, suggestedLine))
                .isInstanceOf(DataIntegrityViolationException.class);
        long paymentId = recordCreditPayment("10.00");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bank_statement_lines SET matched_payment_id = ? WHERE id = ?", paymentId, otherLine))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ignoringALineNeedsAReasonAndIsAudited() throws Exception {
        long importId = json(importStatement(HEADER + today + ",SMS CHARGES,17.70,0,100.00,\n")
                .andExpect(status().isCreated())).get("id").asLong();
        long lineId = lines(importId).get(0).get("id").asLong();

        updateLine(lineId, Map.of("status", "IGNORED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_LINE_IGNORE_REASON_REQUIRED"));
        updateLine(lineId, Map.of("status", "IGNORED", "matchNotes", "Bank SMS charges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("IGNORED"));

        assertThat(auditCount("BANK_LINE_IGNORED")).isEqualTo(1);
        JsonNode summary = json(perform(get("/api/pumps/{pumpId}/bank-reconciliation/imports", setup.pumpId()), setup.owner()));
        assertThat(summary.get(0).get("ignoredLines").asInt()).isEqualTo(1);
        assertThat(summary.get(0).get("unmatchedDebitAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void reRunningAutoMatchPicksUpASettlementRecordedAfterTheImport() throws Exception {
        long importId = json(importStatement(HEADER + today + ",UPI,0,400.00,400.00,\n")
                .andExpect(status().isCreated())).get("id").asLong();
        closeShiftWithUpi("400.00");
        recordUpiSettlement("400.00", null);

        perform(post("/api/pumps/{pumpId}/bank-reconciliation/imports/{id}/auto-match", setup.pumpId(), importId), setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoSuggestedLines").value(1));
        perform(post("/api/pumps/{pumpId}/bank-reconciliation/imports/{id}/auto-match", setup.pumpId(), importId), setup.manager())
                .andExpect(status().isForbidden());
    }

    @Test
    void aCreditPaymentIsMatchedToOneLineOnlyAndOnlyWithAnEqualAmountOrANote() throws Exception {
        long paymentId = recordCreditPayment("500.00");
        long importId = json(importStatement(HEADER
                + today + ",Payment A,0,500.00,500.00,UTR-" + shortId() + "\n"
                + today + ",Payment again,0,500.00,1000.00,UTR-" + shortId() + "\n"
                + today + ",Short payment,0,480.00,1480.00,UTR-" + shortId() + "\n")
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode lines = lines(importId);
        long firstLine = lines.get(0).get("id").asLong();
        long secondLine = lines.get(1).get("id").asLong();
        long shortLine = lines.get(2).get("id").asLong();

        updateLine(firstLine, Map.of("status", "MATCHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("exactly one")));
        updateLine(firstLine, Map.of("status", "MATCHED", "matchedPaymentId", paymentId)).andExpect(status().isOk());
        updateLine(secondLine, Map.of("status", "MATCHED", "matchedPaymentId", paymentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already matched to another bank line")));

        updateLine(firstLine, Map.of("status", "UNMATCHED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedPaymentId").doesNotExist());
        updateLine(shortLine, Map.of("status", "MATCHED", "matchedPaymentId", paymentId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("does not equal")));
        updateLine(shortLine, Map.of("status", "MATCHED", "matchedPaymentId", paymentId,
                "matchNotes", "Bank deducted ₹20 charges"))
                .andExpect(status().isOk());

        JsonNode summary = json(perform(get("/api/pumps/{pumpId}/bank-reconciliation/imports", setup.pumpId()), setup.owner()));
        assertThat(summary.get(0).get("matchedLines").asInt()).isEqualTo(1);
    }

    private ResultActions importStatement(String csv) throws Exception {
        return importStatement(csv, null);
    }

    private ResultActions importStatement(String csv, String dateFormat) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        var request = multipart("/api/pumps/{pumpId}/bank-reconciliation/import", setup.pumpId())
                .file(file).param("bankName", "SBI");
        if (dateFormat != null) {
            request.param("dateFormat", dateFormat);
        }
        return perform(request, setup.owner());
    }

    private JsonNode lines(long importId) throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/bank-reconciliation/imports/{id}/lines", setup.pumpId(), importId),
                setup.owner()).andExpect(status().isOk()));
    }

    private ResultActions updateLine(long lineId, Map<String, Object> body) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/bank-reconciliation/lines/{lineId}", setup.pumpId(), lineId),
                setup.owner(), body);
    }

    private long auditCount(String action) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action::text = ?",
                Long.class, setup.pumpId(), action);
    }

    private ResultActions accept(long lineId) throws Exception {
        return perform(post("/api/pumps/{pumpId}/bank-reconciliation/lines/{lineId}/accept-suggestion", setup.pumpId(), lineId),
                setup.owner());
    }

    private ResultActions voidSettlement(long settlementId) throws Exception {
        return perform(post("/api/pumps/{pumpId}/settlements/{id}/void", setup.pumpId(), settlementId), setup.owner(),
                Map.of("reason", "Entered twice"));
    }

    private long openShift() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private void closeShiftWithUpi(String upi) throws Exception {
        long shiftId = openShift();
        BigDecimal upiAmount = new BigDecimal(upi);
        Map<String, Object> close = new HashMap<>();
        close.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        close.put("cashCollected", new BigDecimal("1000.00").subtract(upiAmount));
        close.put("upiCollected", upiAmount);
        close.put("cardCollected", BigDecimal.ZERO);
        close.put("fleetCardCollected", BigDecimal.ZERO);
        close.put("creditTotal", BigDecimal.ZERO);
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), close)
                .andExpect(status().is2xxSuccessful());
    }

    private long recordUpiSettlement(String amount, String bankReference) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("paymentType", "UPI");
        body.put("settlementDate", today);
        body.put("amountReceived", new BigDecimal(amount));
        body.put("bankReference", bankReference);
        return json(perform(post("/api/pumps/{pumpId}/settlements", setup.pumpId()), setup.manager(), body)
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long recordDeposit(String amount, String slipReference) throws Exception {
        long shiftId = openShift();
        long dropId = json(perform(post("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId),
                setup.operator(), Map.of("amount", new BigDecimal(amount)))).get("id").asLong();
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.manager())
                .andExpect(status().isOk());
        return json(perform(post("/api/pumps/{pumpId}/safe/deposits", setup.pumpId()), setup.manager(),
                Map.of("amount", new BigDecimal(amount), "bankAccount", "SBI ••1234", "depositDate", today,
                        "slipReference", slipReference))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long recordSupplierPayment(String amount) throws Exception {
        long supplierId = json(perform(post("/api/pumps/{pumpId}/suppliers", setup.pumpId()), setup.owner(),
                Map.of("name", "IOCL " + shortId(), "phone", "9876543210"))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        return json(perform(post("/api/pumps/{pumpId}/supplier-payments", setup.pumpId()), setup.owner(),
                Map.of("supplierId", supplierId, "amount", new BigDecimal(amount),
                        "paymentDate", today, "paymentMode", "BANK_TRANSFER"))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private long recordCreditPayment(String amount) throws Exception {
        long shiftId = openShift();
        Map<String, Object> client = new HashMap<>();
        client.put("name", "Client " + shortId());
        client.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        long clientId = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), client)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.operator(),
                Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal(amount), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful());
        return json(perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId),
                setup.owner(), Map.of("amount", new BigDecimal(amount), "paymentMode", "UPI", "paidAt", today))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }
}
