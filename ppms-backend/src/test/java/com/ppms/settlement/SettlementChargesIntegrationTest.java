package com.ppms.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Settlements carry the bank's MDR and GST on it: the gross clears collections, the net is what reached the bank. */
class SettlementChargesIntegrationTest extends ApiIntegrationTest {

    private PumpSetup setup;
    private final String today = TestDates.today().toString();

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("5000");
    }

    @Test
    void cardSettlementTypedAsNetClearsItsWholeGrossFromTheWalletAndKeepsTheCharges() throws Exception {
        closeShift("0.00", "1000.00", "0.00");

        JsonNode preview = json(perform(get("/api/pumps/{pumpId}/settlements/charges", setup.pumpId())
                .param("paymentType", "CARD").param("amount", "978.76").param("amountBasis", "NET"), setup.manager())
                .andExpect(status().isOk()));
        assertThat(preview.get("grossAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(preview.get("mdrPercent").decimalValue()).isEqualByComparingTo("1.8");

        JsonNode settlement = json(record(Map.of("paymentType", "CARD", "settlementDate", today,
                "amountReceived", new BigDecimal("978.76"), "amountBasis", "NET")).andExpect(status().isCreated()));

        assertThat(settlement.get("grossAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(settlement.get("amountReceived").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(settlement.get("mdrAmount").decimalValue()).isEqualByComparingTo("18.00");
        assertThat(settlement.get("gstOnMdrAmount").decimalValue()).isEqualByComparingTo("3.24");
        assertThat(settlement.get("chargesAmount").decimalValue()).isEqualByComparingTo("21.24");
        assertThat(settlement.get("netAmount").decimalValue()).isEqualByComparingTo("978.76");
        assertThat(settlement.get("isPartial").asBoolean()).isFalse();
        assertThat(settlement.get("coversFromDate").asText()).isEqualTo(today);
        assertThat(wallet().get("cardPending").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void typedChargesReplaceThePumpDefaults() throws Exception {
        closeShift("0.00", "1000.00", "0.00");

        record(Map.of("paymentType", "CARD", "settlementDate", today, "amountReceived", new BigDecimal("500.00"),
                "amountBasis", "GROSS", "mdrAmount", new BigDecimal("10.00"), "gstOnMdrAmount", new BigDecimal("1.80")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.netAmount").value(488.20))
                .andExpect(jsonPath("$.isPartial").value(true));

        assertThat(wallet().get("cardPending").decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void zeroAmountsAndGstWithoutMdrAreRefused() throws Exception {
        closeShift("500.00", "0.00", "0.00");

        record(Map.of("paymentType", "UPI", "settlementDate", today, "amountReceived", BigDecimal.ZERO))
                .andExpect(status().isBadRequest());
        record(Map.of("paymentType", "UPI", "settlementDate", today, "amountReceived", new BigDecimal("100.00"),
                "gstOnMdrAmount", new BigDecimal("1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_GST_WITHOUT_MDR"));
        record(Map.of("paymentType", "UPI", "settlementDate", today, "amountReceived", new BigDecimal("100.00"),
                "mdrAmount", new BigDecimal("100.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_CHARGES_EXCEED_AMOUNT"));

        assertThat(wallet().get("upiPending").decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void mdrSettingsAreSavedPerPaymentTypeAndUsedForNewSettlements() throws Exception {
        JsonNode defaults = json(perform(get("/api/pumps/{pumpId}/settlement-configs", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        assertThat(configOf(defaults, "UPI").get("mdrPercent").decimalValue()).isEqualByComparingTo("0");
        assertThat(configOf(defaults, "CARD").get("mdrPercent").decimalValue()).isEqualByComparingTo("1.8");
        assertThat(configOf(defaults, "CARD").get("gstOnMdrPercent").decimalValue()).isEqualByComparingTo("18");

        perform(put("/api/pumps/{pumpId}/settlement-configs/UPI", setup.pumpId()), setup.owner(),
                Map.of("alertTime", "18:00", "enabled", true, "mdrPercent", new BigDecimal("11")))
                .andExpect(status().isBadRequest());
        perform(put("/api/pumps/{pumpId}/settlement-configs/UPI", setup.pumpId()), setup.owner(),
                Map.of("alertTime", "18:00", "enabled", true, "mdrPercent", new BigDecimal("0.5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mdrPercent").value(0.5))
                .andExpect(jsonPath("$.gstOnMdrPercent").value(18.0));
        perform(put("/api/pumps/{pumpId}/settlement-configs/UPI", setup.pumpId()), setup.owner(),
                Map.of("alertTime", "19:00", "enabled", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mdrPercent").value(0.5));

        closeShift("1000.00", "0.00", "0.00");
        record(Map.of("paymentType", "UPI", "settlementDate", today, "amountReceived", new BigDecimal("1000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mdrAmount").value(5.00))
                .andExpect(jsonPath("$.gstOnMdrAmount").value(0.90))
                .andExpect(jsonPath("$.netAmount").value(994.10));
    }

    @Test
    void partialMeansTheLastCollectionDayReachedIsOnlyPartlyCleared() throws Exception {
        closeShift("600.00", "0.00", "0.00");

        record(upi("250.00")).andExpect(status().isCreated()).andExpect(jsonPath("$.isPartial").value(true));
        record(upi("350.00")).andExpect(status().isCreated()).andExpect(jsonPath("$.isPartial").value(false));
    }

    @Test
    void bankChargesAreACostInTheDayBalanceSheetAndProfitAndLossWithoutTouchingExpenses() throws Exception {
        closeShift("0.00", "600.00", "200.00");
        record(Map.of("paymentType", "CARD", "settlementDate", today, "amountReceived", new BigDecimal("600.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chargesAmount").value(12.74));
        perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("paidFrom", "BANK", "category", "MAINTENANCE", "amount", new BigDecimal("100.00"), "description", "Repair",
                        "expenseDate", today, "saveDraft", false))
                .andExpect(status().is2xxSuccessful());

        Map<String, Object> request = new HashMap<>();
        request.put("reportType", "DAY");
        request.put("reportDate", today);
        JsonNode sheet = json(perform(post("/api/pumps/{pumpId}/balance-sheets/generate", setup.pumpId()), setup.manager(), request)
                .andExpect(status().is2xxSuccessful()));

        assertThat(sheet.get("totalBankChargesAmount").decimalValue()).isEqualByComparingTo("12.74");
        assertThat(sheet.get("settlementSummary").get("bankChargesOnDate").decimalValue()).isEqualByComparingTo("12.74");
        assertThat(sheet.get("expenses").get("totalAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(sheet.get("totalNetProfit").decimalValue()).isEqualByComparingTo(
                sheet.get("totalGrossProfit").decimalValue().add(sheet.get("totalDipNetAmount").decimalValue())
                        .subtract(new BigDecimal("112.74")));
        long sheetId = sheet.get("id").asLong();
        JsonNode saved = json(perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), sheetId), setup.manager())
                .andExpect(status().isOk()));
        assertThat(saved.get("totalNetProfit").decimalValue()).isEqualByComparingTo(sheet.get("totalNetProfit").decimalValue());

        JsonNode report = json(perform(get("/api/pumps/{pumpId}/reports/profit-loss", setup.pumpId())
                .param("from", today).param("to", today), setup.manager()).andExpect(status().isOk()));
        assertThat(report.get("totalCardRevenue").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(report.get("totalFleetCardRevenue").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(report.get("totalCashRevenue").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(report.get("totalCollected").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(report.get("approvedExpenses").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(report.get("bankCharges").decimalValue()).isEqualByComparingTo("12.74");
        assertThat(report.get("netOperatingProfit").decimalValue()).isEqualByComparingTo(
                report.get("grossProfit").decimalValue().subtract(new BigDecimal("112.74")));
    }

    private void closeShift(String upi, String card, String fleetCard) throws Exception {
        long shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        BigDecimal digital = new BigDecimal(upi).add(new BigDecimal(card)).add(new BigDecimal(fleetCard));
        Map<String, Object> close = new HashMap<>();
        close.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        close.put("cashCollected", new BigDecimal("1000.00").subtract(digital));
        close.put("upiCollected", new BigDecimal(upi));
        close.put("cardCollected", new BigDecimal(card));
        close.put("fleetCardCollected", new BigDecimal(fleetCard));
        close.put("creditTotal", BigDecimal.ZERO);
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), close)
                .andExpect(status().is2xxSuccessful());
    }

    private ResultActions record(Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/settlements", setup.pumpId()), setup.manager(), body);
    }

    private Map<String, Object> upi(String amount) {
        return Map.of("paymentType", "UPI", "settlementDate", today, "amountReceived", new BigDecimal(amount),
                "amountBasis", "GROSS");
    }

    private JsonNode wallet() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/settlements/wallet", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
    }

    private static JsonNode configOf(JsonNode configs, String paymentType) {
        for (JsonNode config : configs) {
            if (paymentType.equals(config.get("paymentType").asText())) {
                return config;
            }
        }
        throw new AssertionError("No config for " + paymentType + " in " + configs);
    }
}
