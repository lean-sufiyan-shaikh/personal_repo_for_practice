package com.ppms.dayclose;

import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Once today is closed, a write dated today is refused in every domain until an owner or admin re-opens the day. */
class ClosedDayGuardIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    private PumpSetup setup;

    @BeforeEach
    void closeToday() throws Exception {
        setup = createPumpWithStock("5000");
        BigDecimal balance = json(perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager()))
                .get("balance").decimalValue();
        perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(), Map.of("countedAmount", balance))
                .andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/day-closes/{date}/close", setup.pumpId(), TODAY), setup.owner(), Map.of())
                .andExpect(status().isOk());
    }

    @Test
    void shiftCannotBeOpenedUntilTheDayIsReopened() throws Exception {
        refusedAsClosed(openShift());

        reopenToday();
        openShift().andExpect(status().is2xxSuccessful());
    }

    @Test
    void deliveriesDipsAndDrainsAreRefused() throws Exception {
        refusedAsClosed(perform(post("/api/inventory/{pumpId}/deliveries", setup.pumpId()), setup.owner(),
                Map.of("tankId", setup.tank().getId(), "quantityDelivered", new BigDecimal("1000"),
                        "costPricePerUnit", new BigDecimal("90.00"), "deliveryDate", TODAY.toString(),
                        "invoiceReference", "INV-" + shortId(),
                        "receiptCheck", receiptCheck(setup.pumpId(), setup.tank().getId(), "5000", "6000"))));
        refusedAsClosed(dipCheck());
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/fuel-dips", setup.pumpId()), setup.owner(),
                Map.of("tankId", setup.tank().getId(), "litresRemoved", new BigDecimal("5"), "reason", "Water check")));

        reopenToday();
        dipCheck().andExpect(status().is2xxSuccessful());
    }

    @Test
    void expensesAndCashMovementsAreRefusedButAnOpenDayStillTakesThem() throws Exception {
        refusedAsClosed(expense(TODAY));
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.manager(),
                Map.of("eventType", "CASH_IN", "amount", new BigDecimal("100.00"), "description", "Float",
                        "eventDate", TODAY.toString())));
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(),
                Map.of("countedAmount", BigDecimal.ZERO)));
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/safe/deposits", setup.pumpId()), setup.manager(),
                Map.of("amount", new BigDecimal("1.00"), "bankAccount", "SBI", "depositDate", TODAY.toString(),
                        "slipReference", "S-" + shortId())));
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/settlements", setup.pumpId()), setup.manager(),
                Map.of("paymentType", "UPI", "settlementDate", TODAY.toString(), "amountReceived", new BigDecimal("10.00"))));

        expense(TODAY.minusDays(1)).andExpect(status().isCreated());
    }

    @Test
    void creditPaymentsAreRefused() throws Exception {
        Map<String, Object> client = new HashMap<>();
        client.put("name", "Client " + shortId());
        client.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        long clientId = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), client)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();

        refusedAsClosed(perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId),
                setup.manager(), Map.of("amount", new BigDecimal("100.00"), "paymentMode", "CASH", "paidAt", TODAY.toString())));
    }

    @Test
    void counterProductStockAndSalesAreRefused() throws Exception {
        long productId = json(perform(post("/api/pumps/{pumpId}/ancillary/products", setup.pumpId()), setup.owner(),
                Map.of("name", "Engine Oil " + shortId(), "brand", "Castrol", "packageSize", new BigDecimal("1"),
                        "unitOfMeasure", "LITRE", "lowStockThreshold", 2, "gstRatePercent", new BigDecimal("18")))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/prices", setup.pumpId(), productId), setup.owner(),
                Map.of("pricePerUnit", new BigDecimal("450.00"))).andExpect(status().is2xxSuccessful());

        refusedAsClosed(stockIn(productId, TODAY));
        stockIn(productId, TODAY.minusDays(2)).andExpect(status().is2xxSuccessful());
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/ancillary/sales", setup.pumpId()), setup.manager(),
                Map.of("productId", productId, "quantityUnits", 1, "sellingPricePerUnit", new BigDecimal("450.00"),
                        "paymentMode", "CASH")));
    }

    @Test
    void meterAmendmentsAndPriceChangesAreRefusedUntilTheDayIsReopened() throws Exception {
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/nozzles/{nozzleId}/reading-adjustments", setup.pumpId(),
                setup.nozzle().getId()), setup.owner(),
                Map.of("adjustmentType", "CUSTOM_READING", "newReading", new BigDecimal("1000.000"), "reason", "Meter replaced")));
        refusedAsClosed(setPrice());

        reopenToday();
        setPrice().andExpect(status().is2xxSuccessful());
    }

    private void refusedAsClosed(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"))
                .andExpect(jsonPath("$.params.date").value(TODAY.toString()));
    }

    private void reopenToday() throws Exception {
        perform(post("/api/pumps/{pumpId}/day-closes/{date}/reopen", setup.pumpId(), TODAY), setup.owner(),
                Map.of("reason", "A late entry")).andExpect(status().isOk());
    }

    private ResultActions openShift() throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()));
    }

    private ResultActions dipCheck() throws Exception {
        return perform(post("/api/inventory/{pumpId}/dip-checks", setup.pumpId()), setup.manager(),
                Map.of("tankId", setup.tank().getId(), "measuredQuantity", new BigDecimal("5000")));
    }

    private ResultActions expense(LocalDate date) throws Exception {
        return perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.owner(),
                Map.of("paidFrom", "BANK", "category", "MAINTENANCE", "amount", new BigDecimal("250.00"), "description", "Hose repair",
                        "expenseDate", date.toString()));
    }

    private ResultActions stockIn(long productId, LocalDate date) throws Exception {
        return perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/deliveries", setup.pumpId(), productId), setup.manager(),
                Map.of("quantityUnits", 5, "costPricePerUnit", new BigDecimal("300.00"), "deliveryDate", date.toString(),
                        "invoiceReference", "AN-" + shortId()));
    }

    private ResultActions setPrice() throws Exception {
        return perform(post("/api/pumps/{pumpId}/fuel-prices", setup.pumpId()), setup.owner(),
                Map.of("fuelType", "PETROL", "pricePerUnit", new BigDecimal("101.00"), "confirmed", true));
    }
}
