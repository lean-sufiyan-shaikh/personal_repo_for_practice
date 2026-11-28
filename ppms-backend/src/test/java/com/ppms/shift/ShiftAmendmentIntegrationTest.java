package com.ppms.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditLogRepository;
import com.ppms.cash.CashEvent;
import com.ppms.cash.CashEventRepository;
import com.ppms.cash.CashEventType;
import com.ppms.inventory.LotConsumption;
import com.ppms.inventory.LotConsumptionRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An owner or admin corrects a closed shift: figures worked out again, stock re-drawn, cash corrected, history kept. */
class ShiftAmendmentIntegrationTest extends ApiIntegrationTest {

    private static final String TODAY = TestDates.today().toString();

    @Autowired private LotConsumptionRepository lotConsumptionRepository;
    @Autowired private CashEventRepository cashEventRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private PumpSetup setup;
    private long shiftId;

    /** A shift that sold 10 L at ₹100 (meter 1000 → 1010) and handed in ₹1000 cash. */
    @BeforeEach
    void closeAShift() throws Exception {
        setup = createPumpWithStock("5000");
        shiftId = openShift();
        Map<String, Object> closing = new HashMap<>();
        closing.put("fuelReadings", List.of(reading("1010.000")));
        closing.put("cashCollected", new BigDecimal("500.00"));
        closing.put("upiCollected", new BigDecimal("500.00"));
        closing.put("cardCollected", BigDecimal.ZERO);
        closing.put("fleetCardCollected", BigDecimal.ZERO);
        closing.put("creditTotal", BigDecimal.ZERO);
        closing.put("creditEntries", List.of());
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), closing)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));
    }

    @Test
    void amendingReadingsAndCollectionsWorksEverythingOutAgainAndKeepsTheHistory() throws Exception {
        long sheetId = json(perform(post("/api/pumps/{pumpId}/balance-sheets/generate", setup.pumpId()), setup.owner(),
                Map.of("reportType", "DAY", "reportDate", TODAY)).andExpect(status().is2xxSuccessful())).get("id").asLong();

        amend(setup.owner(), "1012.000", "700.00", "500.00", "Meter misread at close")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmountDue").value(1200.00))
                .andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));

        assertThat(litresDrawnFromLots()).isEqualByComparingTo("12.000");
        assertThat(tankRepository.findById(setup.tank().getId()).orElseThrow().getCurrentStock()).isEqualByComparingTo("4988.000");
        assertThat(nozzleRepository.findById(setup.nozzle().getId()).orElseThrow().getLastReading()).isEqualByComparingTo("1012.000");
        assertThat(cashEventsOfShift()).extracting(CashEvent::getEventType, event -> event.getAmount().toPlainString())
                .containsExactlyInAnyOrder(tuple(CashEventType.CASH_IN, "500.00"),
                        tuple(CashEventType.CASH_IN, "200.00"));

        JsonNode history = json(perform(get("/api/pumps/{pumpId}/shifts/{id}/amendments", setup.pumpId(), shiftId), setup.manager())
                .andExpect(status().isOk()));
        assertThat(history).singleElement().satisfies(amendment -> {
            assertThat(amendment.get("reason").asText()).isEqualTo("Meter misread at close");
            assertThat(amendment.get("before").get("totalAmountDue").asText()).isEqualTo("1000.00");
            assertThat(amendment.get("after").get("totalAmountDue").asText()).isEqualTo("1200.00");
            assertThat(amendment.get("after").get("litresSold").asText()).isEqualTo("12.000");
            assertThat(amendment.get("amendedByName").asText()).isEqualTo(setup.owner().getFullName());
        });
        assertThat(auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()))
                .anySatisfy(audit -> {
                    assertThat(audit.getAction()).isEqualTo(AuditAction.SHIFT_AMENDED);
                    assertThat(audit.getDescription()).contains("Meter misread at close");
                });
        perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), sheetId), setup.owner())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.staleReasonCode").value("STALE_SHIFT_AMENDED"));
    }

    @Test
    void lowerCashOpensADiscrepancyAndTakesTheDifferenceOutOfTheSafe() throws Exception {
        amend(setup.admin(), "1010.000", "400.00", "500.00", "Bag recounted")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_DISCREPANCY_PENDING"))
                .andExpect(jsonPath("$.discrepancyType").value("SHORT"))
                .andExpect(jsonPath("$.discrepancyAmount").value(100.00));

        assertThat(cashEventsOfShift()).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo(CashEventType.CASH_OUT);
            assertThat(event.getAmount()).isEqualByComparingTo("100.00");
            assertThat(event.getEventDate()).isEqualTo(TestDates.today());
        });
        assertThat(litresDrawnFromLots()).isEqualByComparingTo("10.000");
    }

    @Test
    void readingCannotChangeOnceALaterShiftStartedOnTheNozzleButCollectionsStillCan() throws Exception {
        openShift();

        amend(setup.owner(), "1012.000", "700.00", "500.00", "Meter misread")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIFT_AMEND_LATER_SHIFT_ON_NOZZLE"))
                .andExpect(jsonPath("$.params.nozzleNumbers[0]").value(1));
        amend(setup.owner(), "1010.000", "600.00", "400.00", "UPI slip was for the next shift")
                .andExpect(status().isOk());

        assertThat(nozzleRepository.findById(setup.nozzle().getId()).orElseThrow().getLastReading()).isEqualByComparingTo("1010.000");
    }

    @Test
    void digitalAmountCannotFallBelowWhatIsAlreadySettled() throws Exception {
        perform(post("/api/pumps/{pumpId}/settlements", setup.pumpId()), setup.owner(),
                Map.of("paymentType", "UPI", "settlementDate", TODAY, "amountReceived", new BigDecimal("450.00")))
                .andExpect(status().is2xxSuccessful());

        amend(setup.owner(), "1010.000", "600.00", "400.00", "UPI miscounted")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIFT_AMEND_BELOW_SETTLED"))
                .andExpect(jsonPath("$.params.paymentTypeCode").value("UPI"))
                .andExpect(jsonPath("$.params.lowest").value("450.00"));
        amend(setup.owner(), "1010.000", "550.00", "450.00", "UPI miscounted").andExpect(status().isOk());
    }

    @Test
    void onlyAnOwnerOrAdminAmendsAShiftAndAReasonIsRequired() throws Exception {
        amend(setup.manager(), "1012.000", "700.00", "500.00", "Meter misread").andExpect(status().isForbidden());
        amend(setup.owner(), "1012.000", "700.00", "500.00", " ").andExpect(status().isBadRequest());
    }

    @Test
    void closedDayRefusesTheAmendmentUntilItIsReopened() throws Exception {
        BigDecimal balance = json(perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager()))
                .get("balance").decimalValue();
        perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(), Map.of("countedAmount", balance))
                .andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/day-closes/{date}/close", setup.pumpId(), TODAY), setup.owner(), Map.of())
                .andExpect(status().isOk());

        amend(setup.owner(), "1012.000", "700.00", "500.00", "Meter misread")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"));

        perform(post("/api/pumps/{pumpId}/day-closes/{date}/reopen", setup.pumpId(), TODAY), setup.owner(),
                Map.of("reason", "Meter misread at close")).andExpect(status().isOk());
        amend(setup.owner(), "1012.000", "700.00", "500.00", "Meter misread").andExpect(status().isOk());
    }

    private long openShift() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", saveUser(UserRole.OPERATOR, setup.pumpId()).getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private ResultActions amend(User user, String endReading, String bagCash, String upi, String reason)
            throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/amend", setup.pumpId(), shiftId), user,
                amendment(endReading, bagCash, upi, reason));
    }

    private Map<String, Object> amendment(String endReading, String bagCash, String upi, String reason) {
        return Map.of("fuelReadings", List.of(reading(endReading)),
                "cashCollected", new BigDecimal(bagCash), "upiCollected", new BigDecimal(upi),
                "cardCollected", BigDecimal.ZERO, "fleetCardCollected", BigDecimal.ZERO, "reason", reason);
    }

    private Map<String, Object> reading(String endReading) {
        return Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal(endReading));
    }

    private BigDecimal litresDrawnFromLots() {
        return lotConsumptionRepository.findByShiftId(shiftId).stream()
                .map(LotConsumption::getQuantityConsumed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<CashEvent> cashEventsOfShift() {
        return cashEventRepository.findAll().stream()
                .filter(event -> Long.valueOf(shiftId).equals(event.getShiftId()))
                .toList();
    }
}
