package com.ppms.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.fuel.FuelType;
import com.ppms.pump.Nozzle;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Price changes during a shift, discrepancy controls, stock shortfalls and the real close time, end to end. */
class ShiftCloseControlsIntegrationTest extends ApiIntegrationTest {

    private static final String CLOSE_PATH = "/api/pumps/{pumpId}/shifts/{id}/close";
    private static final String RESOLVE_PATH = "/api/pumps/{pumpId}/shifts/{id}/discrepancy-resolution";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShiftRepository shiftRepository;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("5000");
    }

    @Test
    void priceChangeDuringAShiftChargesLitresBeforeTheReadingAtTheOldPriceAndAfterAtTheNew() throws Exception {
        long shiftId = openShift();
        JsonNode openNozzles = json(perform(get("/api/pumps/{pumpId}/fuel-prices/open-nozzles", setup.pumpId())
                .param("fuelType", "PETROL"), setup.manager()).andExpect(status().isOk()));
        assertThat(openNozzles).hasSize(1);
        assertThat(openNozzles.get(0).get("lastKnownReading").decimalValue()).isEqualByComparingTo("1000");

        perform(post("/api/pumps/{pumpId}/fuel-prices", setup.pumpId()), setup.owner(),
                Map.of("fuelType", "PETROL", "pricePerUnit", new BigDecimal("110.00"), "confirmed", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGE_METER_READINGS_MISSING"));
        JsonNode priceSet = json(perform(post("/api/pumps/{pumpId}/fuel-prices", setup.pumpId()), setup.owner(),
                Map.of("fuelType", "PETROL", "pricePerUnit", new BigDecimal("110.00"), "confirmed", true,
                        "openNozzleReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "meterReading", "1010.000"))))
                .andExpect(status().isCreated()));
        assertThat(priceSet.get("splitNozzleCount").asInt()).isEqualTo(1);

        perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), closeRequest("1005.000", "0", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLOSING_READING_BELOW_PRICE_CHANGE"));
        JsonNode closed = json(perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1030.000", "3200.00", null)).andExpect(status().isOk()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED_BALANCED");
        assertThat(closed.get("totalAmountDue").decimalValue()).isEqualByComparingTo("3200.00");
        JsonNode segments = closed.get("fuelReadings").get(0).get("priceSegments");
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).get("pricePerUnit").decimalValue()).isEqualByComparingTo("100");
        assertThat(segments.get(0).get("unitsSold").decimalValue()).isEqualByComparingTo("10");
        assertThat(segments.get(1).get("pricePerUnit").decimalValue()).isEqualByComparingTo("110");
        assertThat(segments.get(1).get("unitsSold").decimalValue()).isEqualByComparingTo("20");

        JsonNode sheet = json(perform(post("/api/pumps/{pumpId}/balance-sheets/generate", setup.pumpId()), setup.manager(),
                Map.of("reportType", "DAY", "reportDate", TestDates.today().toString()))
                .andExpect(status().is2xxSuccessful()));
        assertThat(sheet.get("totalExpectedRevenue").decimalValue()).isEqualByComparingTo("3200.00");
        assertThat(sheet.get("fuelLines").get(0).get("expectedRevenue").decimalValue()).isEqualByComparingTo("3200.00");
    }

    @Test
    void managerWhoClosedTheShiftCannotWaiveItsDiscrepancyButTheOwnerCan() throws Exception {
        long shiftId = closeShort("200.00");

        perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.manager(),
                Map.of("resolutionAction", "WAIVED", "resolutionNote", "Small amount"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WAIVER_BY_CLOSER_NOT_ALLOWED"));
        perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.owner(),
                Map.of("resolutionAction", "WAIVED", "resolutionNote", "Small amount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_DISCREPANCY_RESOLVED"));
    }

    @Test
    void newPumpEscalatesAboveFiveHundredAndAChangedThresholdReDecidesPendingDiscrepancies() throws Exception {
        assertThat(pumpLocationRepository.findById(setup.pumpId()).orElseThrow().getDiscrepancyEscalationThreshold())
                .isEqualByComparingTo("500.00");
        long shiftId = closeShort("600.00");
        assertThat(statusOf(shiftId)).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL);

        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("discrepancyEscalationThreshold", 1000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancyEscalationThreshold").value(1000.0));
        assertThat(statusOf(shiftId)).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("discrepancyEscalationThreshold", 0)).andExpect(status().isOk());
        assertThat(statusOf(shiftId)).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL);
    }

    @Test
    void closingShortOrOverWithoutAReasonIsRefusedByTheServer() throws Exception {
        long shiftId = openShift();

        perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), closeRequest("1010.000", "900.00", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCREPANCY_REASON_REQUIRED"));
        assertThat(statusOf(shiftId)).isEqualTo(ShiftStatus.OPEN);
    }

    @Test
    void differenceWithinTheRoundingToleranceClosesBalancedWithoutAReason() throws Exception {
        long shiftId = openShift();

        JsonNode closed = json(perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1010.000", "999.50", null)).andExpect(status().isOk()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED_BALANCED");
        assertThat(closed.get("discrepancyAmount").isNull()).isTrue();
        assertThat(closed.get("roundingAdjustment").decimalValue()).isEqualByComparingTo("-0.50");
    }

    @Test
    void partOfAShortageCanBeRecoveredInCashLeavingTheRestToResolveAnotherWay() throws Exception {
        long shiftId = closeShort("300.00");

        perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.owner(),
                Map.of("resolutionAction", "CASH_RECOVERY", "recoveredAmount", 400))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_RECOVERY_AMOUNT_INVALID"));
        JsonNode partly = json(perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.owner(),
                Map.of("resolutionAction", "CASH_RECOVERY", "recoveredAmount", 100)).andExpect(status().isOk()));
        assertThat(partly.get("status").asText()).isEqualTo("CLOSED_DISCREPANCY_PENDING");
        assertThat(partly.get("cashRecoveryAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(partly.get("discrepancyResolution").isNull()).isTrue();
        perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.owner(),
                Map.of("resolutionAction", "CASH_RECOVERY", "recoveredAmount", 50))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_RECOVERY_ALREADY_RECORDED"));

        JsonNode pendingForPayroll = json(perform(get("/api/pumps/{pumpId}/payroll/pending-discrepancies", setup.pumpId())
                .param("userId", setup.operator().getId().toString()), setup.owner()).andExpect(status().isOk()));
        assertThat(pendingForPayroll.get(0).get("discrepancyAmount").decimalValue()).isEqualByComparingTo("200.00");
        perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.owner(), Map.of("resolutionAction", "SALARY_DEDUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_DISCREPANCY_RESOLVED"));

        JsonNode sheet = json(perform(post("/api/pumps/{pumpId}/balance-sheets/generate", setup.pumpId()), setup.manager(),
                Map.of("reportType", "DAY", "reportDate", TestDates.today().toString()))
                .andExpect(status().is2xxSuccessful()));
        assertThat(sheet.get("totalCashRecovery").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    void everyInvestigationNoteIsKept() throws Exception {
        long shiftId = closeShort("200.00");

        perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.manager(),
                Map.of("resolutionAction", "PENDING_INVESTIGATION", "resolutionNote", "Checking CCTV"))
                .andExpect(status().isOk());
        JsonNode second = json(perform(patch(RESOLVE_PATH, setup.pumpId(), shiftId), setup.owner(),
                Map.of("resolutionAction", "PENDING_INVESTIGATION", "resolutionNote", "CCTV shows a drive-off"))
                .andExpect(status().isOk()));

        assertThat(second.get("investigationNote").asText()).isEqualTo("CCTV shows a drive-off");
        assertThat(second.get("investigatedByName").asText()).isEqualTo(setup.manager().getFullName());
        JsonNode history = second.get("investigationHistory");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("note").asText()).isEqualTo("Checking CCTV");
        assertThat(history.get(1).get("writtenByName").asText()).isEqualTo(setup.owner().getFullName());
    }

    @Test
    void anOperatorOpenedOnTwoDispensersAtOnceGetsOnlyOneShift() throws Exception {
        Nozzle second = nozzleRepository.save(Nozzle.builder()
                .duId(setup.du().getId())
                .nozzleNumber(2)
                .fuelType(FuelType.PETROL)
                .lastReading(new BigDecimal("500.000"))
                .tankId(setup.tank().getId())
                .build());
        loginAs(setup.manager());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (Long nozzleId : List.of(setup.nozzle().getId(), second.getId())) {
                Callable<MvcResult> open = () -> {
                    start.await();
                    return perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                            Map.of("duId", setup.du().getId(), "nozzleIds", List.of(nozzleId),
                                    "operatorId", setup.operator().getId())).andReturn();
                };
                results.add(executor.submit(open));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            List<String> bodies = new ArrayList<>();
            for (Future<MvcResult> result : results) {
                MvcResult done = result.get(30, TimeUnit.SECONDS);
                statuses.add(done.getResponse().getStatus());
                bodies.add(done.getResponse().getContentAsString());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 400);
            assertThat(String.join("", bodies)).containsAnyOf("OPERATOR_SHIFT_ALREADY_OPEN", "OPERATOR_SHIFT_OPENED_CONCURRENTLY");
        } finally {
            executor.shutdownNow();
        }
        Integer openShifts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shifts WHERE operator_id = ? AND status IN ('OPEN', 'OPEN_OVERDUE')",
                Integer.class, setup.operator().getId());
        assertThat(openShifts).isEqualTo(1);
    }

    @Test
    void lateCloseKeepsTheScheduledEndForSheetsAndTheRealCloseTimeForTheDutyReport() throws Exception {
        long shiftId = openShift();
        jdbcTemplate.update("UPDATE shifts SET shift_date = shift_date - 1 WHERE id = ?", shiftId);

        JsonNode closed = json(perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1010.000", "1000.00", null)).andExpect(status().isOk()));

        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        assertThat(shift.getClosedAt()).isAfter(shift.getActualEndTime());
        assertThat(closed.get("closedAt").isNull()).isFalse();
        JsonNode duty = json(perform(get("/api/pumps/{pumpId}/reports/operator-duty", setup.pumpId())
                .param("operatorId", setup.operator().getId().toString())
                .param("from", TestDates.today().minusDays(1).toString())
                .param("to", TestDates.today().toString()), setup.owner()).andExpect(status().isOk()));
        JsonNode line = duty.get("shifts").get(0);
        assertThat(line.get("closedAt").asText()).isNotEqualTo(line.get("actualEndTime").asText());
        assertThat(java.time.OffsetDateTime.parse(line.get("closedAt").asText()).toInstant())
                .isEqualTo(shift.getClosedAt().toInstant());
    }

    @Test
    void sellingMoreThanBookStockClosesTheShiftAndAskForTheMissingDeliveryUntilItIsRecorded() throws Exception {
        PumpSetup lowStock = createPumpWithStock("100");
        setup = lowStock;
        long shiftId = openShift();

        JsonNode closed = json(perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1150.000", "15000.00", null)).andExpect(status().isOk()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED_BALANCED");
        assertThat(closed.get("stockShortfall").asBoolean()).isTrue();
        assertThat(closed.get("fuelReadings").get(0).get("stockShortfallLitres").decimalValue()).isEqualByComparingTo("50");
        assertThat(tankRepository.findById(setup.tank().getId()).orElseThrow().getCurrentStock()).isEqualByComparingTo("0");
        assertThat(attentionTypes()).contains("STOCK_SHORTFALL");

        recordDelivery(setup, "1000", "90.00", TestDates.today());

        assertThat(tankRepository.findById(setup.tank().getId()).orElseThrow().getCurrentStock()).isEqualByComparingTo("950");
        assertThat(attentionTypes()).doesNotContain("STOCK_SHORTFALL");
        Integer consumed = jdbcTemplate.queryForObject(
                "SELECT CAST(SUM(quantity_consumed) AS INTEGER) FROM lot_consumptions WHERE shift_id = ?", Integer.class, shiftId);
        assertThat(consumed).isEqualTo(150);
    }

    @Test
    void creditAmountsEnteredAtCloseAreRoundedToPaise() throws Exception {
        JsonNode client = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(),
                Map.of("name", "Fleet Co " + shortId(), "phone", "9" + shortId().replaceAll("[^0-9]", "1").substring(0, 4) + "12345"))
                .andExpect(status().is2xxSuccessful()));
        long shiftId = openShift();
        Map<String, Object> entry = new HashMap<>();
        entry.put("clientName", client.get("name").asText());
        entry.put("clientId", client.get("id").asLong());
        entry.put("amount", new BigDecimal("100.555"));
        entry.put("fuelType", "PETROL");
        Map<String, Object> request = closeRequest("1010.000", "899.44", null);
        request.put("creditTotal", new BigDecimal("100.555"));
        request.put("creditEntries", List.of(entry));

        JsonNode closed = json(perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), request)
                .andExpect(status().isOk()));

        assertThat(closed.get("creditEntries").get(0).get("amount").decimalValue()).isEqualTo(new BigDecimal("100.56"));
    }

    @Test
    void openingTheSameShiftAgainWithItsKeyReturnsTheShiftAlreadyOpened() throws Exception {
        Map<String, Object> request = Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                "operatorId", setup.operator().getId());
        String key = "open-" + shortId() + "-retry";

        JsonNode first = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId())
                .header("Idempotency-Key", key), setup.manager(), request).andExpect(status().isCreated()));
        JsonNode retry = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId())
                .header("Idempotency-Key", key), setup.manager(), request).andExpect(status().isCreated()));

        assertThat(retry.get("id").asLong()).isEqualTo(first.get("id").asLong());
        perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(), request)
                .andExpect(status().isBadRequest());
    }

    private List<String> attentionTypes() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/attention", setup.pumpId()), setup.owner())
                .andExpect(status().isOk())).findValuesAsText("type");
    }

    private ShiftStatus statusOf(long shiftId) {
        return shiftRepository.findById(shiftId).orElseThrow().getStatus();
    }

    private long closeShort(String shortBy) throws Exception {
        long shiftId = openShift();
        String cash = new BigDecimal("1000.00").subtract(new BigDecimal(shortBy)).toPlainString();
        perform(post(CLOSE_PATH, setup.pumpId(), shiftId), setup.manager(), closeRequest("1010.000", cash, "Short at close"))
                .andExpect(status().isOk());
        return shiftId;
    }

    private long openShift() throws Exception {
        JsonNode opened = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().isCreated()));
        return opened.get("id").asLong();
    }

    private Map<String, Object> closeRequest(String endReading, String cash, String reason) {
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal(endReading))));
        request.put("cashCollected", new BigDecimal(cash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        request.put("discrepancyReason", reason);
        return request;
    }
}
