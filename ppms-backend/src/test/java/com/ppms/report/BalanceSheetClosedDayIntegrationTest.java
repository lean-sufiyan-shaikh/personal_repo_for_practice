package com.ppms.report;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A DAY sheet goes stale when anything it shows changes later, its detail shows how far the live lines moved, and a
 * closed day's sheets only change after the day is re-opened, the re-closed day then pointing at the new sheet.
 */
class BalanceSheetClosedDayIntegrationTest extends ApiIntegrationTest {

    private static final String TODAY = TestDates.today().toString();

    private PumpSetup setup;
    private long clientId;

    @BeforeEach
    void closeAShiftWithCreditAndUpi() throws Exception {
        setup = createPumpWithStock("5000");
        Map<String, Object> client = new HashMap<>();
        client.put("name", "Client " + shortId());
        client.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        clientId = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), client)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        long shiftId = openShift();
        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.manager(),
                Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal("300.00"), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful());
        closeShift(shiftId, "500.00", "200.00", "300.00").andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));
    }

    @Test
    void eachLaterChangeMarksTheDaySheetStale() throws Exception {
        long sheetId = generateDaySheet();

        recordDelivery(setup, "1000", "90.00", TestDates.today());
        sheetId = expectStaleThenRegenerate(sheetId, "STALE_DELIVERY_CHANGED");

        perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("paidFrom", "BANK", "category", "MAINTENANCE", "amount", new BigDecimal("250.00"), "description", "Hose repair",
                        "expenseDate", TODAY)).andExpect(status().isCreated());
        perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), sheetId), setup.owner())
                .andExpect(jsonPath("$.linesChangedSinceGenerated").value(true))
                .andExpect(jsonPath("$.expensesChangeSinceGenerated").value(250.00))
                .andExpect(jsonPath("$.dipNetChangeSinceGenerated").value(0.00));
        sheetId = expectStaleThenRegenerate(sheetId, "STALE_EXPENSE_CHANGED");
        perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), sheetId), setup.owner())
                .andExpect(jsonPath("$.linesChangedSinceGenerated").value(false))
                .andExpect(jsonPath("$.expensesChangeSinceGenerated").value(0.00));

        perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId), setup.owner(),
                Map.of("amount", new BigDecimal("100.00"), "paymentMode", "CASH", "paidAt", TODAY))
                .andExpect(status().is2xxSuccessful());
        sheetId = expectStaleThenRegenerate(sheetId, "STALE_CREDIT_PAYMENT_CHANGED");

        perform(post("/api/pumps/{pumpId}/settlements", setup.pumpId()), setup.owner(),
                Map.of("paymentType", "UPI", "settlementDate", TODAY, "amountReceived", new BigDecimal("200.00")))
                .andExpect(status().is2xxSuccessful());
        sheetId = expectStaleThenRegenerate(sheetId, "STALE_SETTLEMENT_CHANGED");

        perform(post("/api/pumps/{pumpId}/nozzles/{nozzleId}/reading-adjustments", setup.pumpId(), setup.nozzle().getId()),
                setup.owner(), Map.of("adjustmentType", "CUSTOM_READING", "newReading", new BigDecimal("1010.000"),
                        "reason", "Meter checked")).andExpect(status().is2xxSuccessful());
        sheetId = expectStaleThenRegenerate(sheetId, "STALE_METER_AMENDED");

        long shortShift = openShift();
        closeShift(shortShift, "900.00", "0.00", "0.00").andExpect(jsonPath("$.status").value("CLOSED_DISCREPANCY_PENDING"));
        sheetId = regenerate(sheetId);
        perform(patch("/api/pumps/{pumpId}/shifts/{id}/discrepancy-resolution", setup.pumpId(), shortShift), setup.owner(),
                Map.of("resolutionAction", "WAIVED", "resolutionNote", "Change given from own pocket"))
                .andExpect(status().isOk());
        expectStaleThenRegenerate(sheetId, "STALE_DISCREPANCY_RESOLVED");
    }

    @Test
    void closedDaySheetsChangeOnlyAfterAReopenAndTheReclosedDayPointsAtTheNewSheet() throws Exception {
        BigDecimal balance = json(perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager()))
                .get("balance").decimalValue();
        perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(), Map.of("countedAmount", balance))
                .andExpect(status().isCreated());
        long closedSheetId = json(perform(post("/api/pumps/{pumpId}/day-closes/{date}/close", setup.pumpId(), TODAY),
                setup.manager(), Map.of()).andExpect(status().isOk())).get("balanceSheetId").asLong();

        refusedAsClosed(perform(delete("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), closedSheetId), setup.owner()));
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/balance-sheets/{id}/regenerate", setup.pumpId(), closedSheetId),
                setup.owner()));
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/balance-sheets/generate", setup.pumpId()), setup.owner(),
                Map.of("reportType", "SHIFT", "reportDate", TODAY, "shiftDefinitionId", setup.allDayShift().getId())));

        perform(post("/api/pumps/{pumpId}/day-closes/{date}/reopen", setup.pumpId(), TODAY), setup.owner(),
                Map.of("reason", "Late delivery bill")).andExpect(status().isOk());
        perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), closedSheetId), setup.owner())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReasonCode").value("STALE_DAY_REOPENED"));

        long newSheetId = regenerate(closedSheetId);
        perform(get("/api/pumps/{pumpId}/day-closes/{date}", setup.pumpId(), TODAY), setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceSheetId").value(newSheetId));

        long reclosedSheetId = json(perform(post("/api/pumps/{pumpId}/day-closes/{date}/close", setup.pumpId(), TODAY),
                setup.owner(), Map.of()).andExpect(status().isOk())).get("balanceSheetId").asLong();
        assertThat(reclosedSheetId).isNotEqualTo(closedSheetId);
        perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), reclosedSheetId), setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(false));
    }

    private long generateDaySheet() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/balance-sheets/generate", setup.pumpId()), setup.owner(),
                Map.of("reportType", "DAY", "reportDate", TODAY)).andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private long expectStaleThenRegenerate(long sheetId, String reasonCode) throws Exception {
        perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), sheetId), setup.owner())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReasonCode").value(reasonCode));
        return regenerate(sheetId);
    }

    private long regenerate(long sheetId) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/balance-sheets/{id}/regenerate", setup.pumpId(), sheetId), setup.owner())
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long openShift() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    /** Sells 10 L at ₹100. */
    private ResultActions closeShift(long shiftId, String bagCash, String upi, String creditTotal) throws Exception {
        BigDecimal endReading = nozzleRepository.findById(setup.nozzle().getId()).orElseThrow().getLastReading()
                .add(BigDecimal.TEN);
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", endReading)));
        request.put("cashCollected", new BigDecimal(bagCash));
        request.put("upiCollected", new BigDecimal(upi));
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", new BigDecimal(creditTotal));
        request.put("creditEntries", List.of());
        request.put("discrepancyReason", "Counted short");
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), request)
                .andExpect(status().isOk());
    }

    private void refusedAsClosed(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"))
                .andExpect(jsonPath("$.params.date").value(TODAY));
    }
}
