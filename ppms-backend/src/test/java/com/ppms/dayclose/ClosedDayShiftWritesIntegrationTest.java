package com.ppms.dayclose;

import com.ppms.cash.CashEvent;
import com.ppms.cash.CashEventRepository;
import com.ppms.cash.CashEventType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

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

/**
 * Every write on a shift belongs to the shift's business day: closing it, its credit sales, its handover and its
 * cash drops are refused while that day is closed, and the close's cash and credit are dated on that day.
 */
class ClosedDayShiftWritesIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate YESTERDAY = TestDates.today().minusDays(1);

    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftCreditEntryRepository creditEntryRepository;
    @Autowired private CashEventRepository cashEventRepository;
    @Autowired private DayCloseRepository dayCloseRepository;
    @Autowired private DayCloseVersionRepository versionRepository;

    private PumpSetup setup;
    private long shiftId;
    private long clientId;

    @BeforeEach
    void openAShiftOfYesterday() throws Exception {
        setup = createPumpWithStock("5000");
        shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        Shift shift = shiftRepository.findById(shiftId).orElseThrow();
        shift.setShiftDate(YESTERDAY);
        shiftRepository.save(shift);
        Map<String, Object> client = new HashMap<>();
        client.put("name", "Client " + shortId());
        client.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        clientId = json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), client)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    @Test
    void nightShiftClosedAfterMidnightDatesItsCashAndCreditOnTheShiftDate() throws Exception {
        sellOnCredit("200.00").andExpect(status().is2xxSuccessful());

        closeShift("700.00", List.of(Map.of("clientName", "Walk-in fleet", "clientId", clientId,
                "amount", new BigDecimal("100.00"), "fuelType", "PETROL")), "300.00")
                .andExpect(status().isOk());

        assertThat(creditEntryRepository.findByShiftId(shiftId)).hasSize(2)
                .allSatisfy(entry -> assertThat(entry.getSaleDate()).isEqualTo(YESTERDAY));
        assertThat(cashEventsOfShift()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(CashEventType.CASH_IN);
            assertThat(event.getEventDate()).isEqualTo(YESTERDAY);
            assertThat(event.getAmount()).isEqualByComparingTo("700.00");
        });
    }

    @Test
    void everyWriteOnAShiftOfAClosedDayIsRefusedUntilTheDayIsReopened() throws Exception {
        long entryId = json(sellOnCredit("200.00")).get("creditEntries").get(0).get("id").asLong();
        long dropId = json(recordDrop().andExpect(status().isCreated())).get("id").asLong();
        long secondDropId = json(recordDrop().andExpect(status().isCreated())).get("id").asLong();
        markClosed(YESTERDAY);

        refusedAsClosed(closeShift("1000.00", List.of(), "200.00"));
        refusedAsClosed(sellOnCredit("50.00"));
        refusedAsClosed(perform(patch("/api/pumps/{pumpId}/shifts/{shiftId}/credit-entries/{entryId}/void",
                setup.pumpId(), shiftId, entryId), setup.manager(), Map.of("voidReason", "Typed twice")));
        refusedAsClosed(perform(post("/api/pumps/{pumpId}/handovers", setup.pumpId()), setup.manager(),
                Map.of("outgoingShiftId", shiftId, "incomingOperatorId", saveUser(UserRole.OPERATOR, setup.pumpId()).getId(),
                        "closing", closing("1000.00", List.of(), "200.00"))));
        refusedAsClosed(recordDrop());
        refusedAsClosed(perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.manager()));
        refusedAsClosed(perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/reject", setup.pumpId(), secondDropId),
                setup.manager(), Map.of("reason", "Not in the safe")));

        perform(post("/api/pumps/{pumpId}/day-closes/{date}/reopen", setup.pumpId(), YESTERDAY), setup.owner(),
                Map.of("reason", "Night shift still open")).andExpect(status().isOk());
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.manager())
                .andExpect(status().isOk());
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/reject", setup.pumpId(), secondDropId), setup.manager(),
                Map.of("reason", "Not in the safe")).andExpect(status().isOk());
        closeShift("700.00", List.of(), "200.00").andExpect(status().isOk());

        assertThat(cashEventsOfShift()).extracting(CashEvent::getEventDate).containsOnly(YESTERDAY);
        assertThat(creditEntryRepository.findByShiftId(shiftId)).extracting(ShiftCreditEntry::getSaleDate).containsOnly(YESTERDAY);
    }

    /** A day closed while its shift was still open, as the old warning-only rule allowed. */
    private void markClosed(LocalDate date) {
        DayClose close = dayCloseRepository.save(DayClose.builder()
                .pumpId(setup.pumpId())
                .businessDate(date)
                .status(DayCloseStatus.CLOSED)
                .currentVersion(1)
                .closedByUserId(setup.manager().getId())
                .closedAt(TestDates.now())
                .build());
        versionRepository.save(DayCloseVersion.builder()
                .dayCloseId(close.getId())
                .version(1)
                .summary(DaySummaryBuilderTest.emptyDaySummary(date))
                .closedByUserId(setup.manager().getId())
                .closedAt(TestDates.now())
                .build());
    }

    private ResultActions sellOnCredit(String amount) throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.manager(),
                Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal(amount), "fuelType", "PETROL"));
    }

    private ResultActions recordDrop() throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId), setup.operator(),
                Map.of("amount", new BigDecimal("100.00")));
    }

    /** Sells 10 L at ₹100. */
    private ResultActions closeShift(String bagCash, List<Map<String, Object>> creditEntries, String creditTotal) throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(),
                closing(bagCash, creditEntries, creditTotal));
    }

    private Map<String, Object> closing(String bagCash, List<Map<String, Object>> creditEntries, String creditTotal) {
        BigDecimal endReading = nozzleRepository.findById(setup.nozzle().getId()).orElseThrow().getLastReading()
                .add(BigDecimal.TEN);
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", endReading)));
        request.put("cashCollected", new BigDecimal(bagCash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", new BigDecimal(creditTotal));
        request.put("creditEntries", creditEntries);
        return request;
    }

    private List<CashEvent> cashEventsOfShift() {
        return cashEventRepository.findAll().stream()
                .filter(event -> Long.valueOf(shiftId).equals(event.getShiftId()))
                .toList();
    }

    private void refusedAsClosed(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"))
                .andExpect(jsonPath("$.params.date").value(YESTERDAY.toString()));
    }
}
