package com.ppms.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test litres poured back into the tank are neither charged to the operator nor taken from stock. */
class TestLitresIntegrationTest extends ApiIntegrationTest {

    private static final String CLOSE_PATH = "/api/pumps/{pumpId}/shifts/{id}/close";

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("5000");
    }

    @Test
    void testLitresComeOffTheAmountDueAndTheStockDeduction() throws Exception {
        long shiftId = openShift();

        JsonNode closed = json(perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1025.000", "5", "2000.00", null)).andExpect(status().isOk()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED_BALANCED");
        assertThat(closed.get("totalAmountDue").decimalValue()).isEqualByComparingTo("2000.00");
        JsonNode reading = closed.get("fuelReadings").get(0);
        assertThat(reading.get("meterLitres").decimalValue()).isEqualByComparingTo("25");
        assertThat(reading.get("testLitres").decimalValue()).isEqualByComparingTo("5");
        assertThat(reading.get("unitsSold").decimalValue()).isEqualByComparingTo("20");
        assertThat(currentStock()).isEqualByComparingTo("4980");
    }

    @Test
    void backfilledShiftLeavesItsTestLitresInTheTank() throws Exception {
        LocalDate yesterday = TestDates.today().minusDays(1);
        Map<String, Object> request = new HashMap<>();
        request.put("shiftDefinitionId", setup.allDayShift().getId());
        request.put("shiftDate", yesterday.toString());
        request.put("duId", setup.du().getId());
        request.put("operatorId", setup.operator().getId());
        request.put("nozzleReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(),
                "openingReading", new BigDecimal("1000.000"), "closingReading", new BigDecimal("1010.000"),
                "testLitres", new BigDecimal("5"))));
        request.put("cashCollected", new BigDecimal("475.00"));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("fuelRateOverrides", Map.of("PETROL", new BigDecimal("95.00")));

        JsonNode backfilled = json(perform(post("/api/pumps/{pumpId}/shifts/backfill", setup.pumpId()), setup.owner(), request)
                .andExpect(status().is2xxSuccessful()));

        assertThat(backfilled.get("status").asText()).isEqualTo("CLOSED_BALANCED");
        assertThat(backfilled.get("fuelReadings").get(0).get("unitsSold").decimalValue()).isEqualByComparingTo("5");
        assertThat(currentStock()).isEqualByComparingTo("4995");
    }

    @Test
    void pumpSettingsKeepTheTestVolumeAndLimitWithinRange() throws Exception {
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("testVolumeLitres", new BigDecimal("150")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEST_VOLUME_OUT_OF_RANGE"));
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("maxTestLitresPerNozzle", new BigDecimal("-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MAX_TEST_LITRES_OUT_OF_RANGE"));
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("testVolumeLitres", new BigDecimal("10"), "maxTestLitresPerNozzle", new BigDecimal("20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testVolumeLitres").value(10.00))
                .andExpect(jsonPath("$.maxTestLitresPerNozzle").value(20.00));
    }

    private long openShift() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private Map<String, Object> closeRequest(String endReading, String testLitres, String cash, String note) {
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(),
                "endReading", new BigDecimal(endReading), "testLitres", new BigDecimal(testLitres))));
        request.put("cashCollected", new BigDecimal(cash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        request.put("testLitresNote", note);
        request.put("discrepancyReason", "Checked");
        return request;
    }

    private BigDecimal currentStock() {
        return tankRepository.findById(setup.tank().getId()).orElseThrow().getCurrentStock();
    }
}
