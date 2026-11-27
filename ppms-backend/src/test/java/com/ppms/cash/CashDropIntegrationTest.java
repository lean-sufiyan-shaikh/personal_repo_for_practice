package com.ppms.cash;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.notification.NotificationRepository;
import com.ppms.notification.NotificationType;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drops during a shift: recorded by the operator, confirmed or rejected by someone else, and only confirmed drops
 * lower the cash expected in the bag at close. A ₹100/L price and a 10 L sale make ₹1000 due in these tests.
 */
class CashDropIntegrationTest extends ApiIntegrationTest {

    @Autowired private CashDropRepository dropRepository;
    @Autowired private CashEventRepository cashEventRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private NotificationRepository notificationRepository;

    private PumpSetup setup;
    private long shiftId;

    @BeforeEach
    void openShift() throws Exception {
        setup = createPumpWithStock("5000");
        shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    @Test
    void operatorRecordsADropThatWaitsForConfirmationAndNotifiesManagers() throws Exception {
        recordDrop(setup.operator(), Map.of("amount", new BigDecimal("600.00"), "denominations", notes(1, 0, 1, 0, 0, 0, "0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.operatorName").value(setup.operator().getFullName()))
                .andExpect(jsonPath("$.denominations.notes500").value(1));

        assertThat(notificationRepository.findAll()).anyMatch(notification -> notification.getPumpId().equals(setup.pumpId())
                && notification.getType() == NotificationType.CASH_DROP_PENDING);
        assertThat(safeBalance()).isEqualByComparingTo("0");
        perform(get("/api/pumps/{pumpId}/cash-drops/pending", setup.pumpId()), setup.manager())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void breakdownThatDoesNotAddUpToTheDropIsRefused() throws Exception {
        recordDrop(setup.operator(), Map.of("amount", new BigDecimal("650.00"), "denominations", notes(1, 0, 1, 0, 0, 0, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DENOMINATIONS_TOTAL_MISMATCH"))
                .andExpect(jsonPath("$.params.denominationTotal").value("600.00"));
        assertThat(dropRepository.findByShiftIdOrderByCreatedAtDescIdDesc(shiftId)).isEmpty();
    }

    @Test
    void confirmingPutsTheDropInTheSafeOnce() throws Exception {
        long dropId = recordedDropId(setup.operator(), "600.00");

        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.manager())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.reviewedByName").value(setup.manager().getFullName()));
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_DROP_NOT_PENDING"));

        assertThat(safeBalance()).isEqualByComparingTo("600.00");
        assertThat(eventsOfType(CashEventType.CASH_DROP)).hasSize(1);
    }

    @Test
    void rejectedDropNeedsAReasonAndNeverReachesTheSafe() throws Exception {
        long dropId = recordedDropId(setup.operator(), "600.00");

        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/reject", setup.pumpId(), dropId), setup.manager(), Map.of("reason", " "))
                .andExpect(status().isBadRequest());
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/reject", setup.pumpId(), dropId), setup.manager(),
                Map.of("reason", "Bag held ₹500 only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Bag held ₹500 only"));

        assertThat(safeBalance()).isEqualByComparingTo("0");
    }

    @Test
    void operatorCannotConfirmOrRejectAndCannotDropFromAnotherOperatorsShift() throws Exception {
        long dropId = recordedDropId(setup.operator(), "600.00");
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.operator())
                .andExpect(status().isForbidden());
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/reject", setup.pumpId(), dropId), setup.operator(),
                Map.of("reason", "No"))
                .andExpect(status().isForbidden());
        perform(get("/api/pumps/{pumpId}/cash-drops/pending", setup.pumpId()), setup.operator()).andExpect(status().isForbidden());

        User otherOperator = saveUser(UserRole.OPERATOR, setup.pumpId());
        recordDrop(otherOperator, Map.of("amount", new BigDecimal("100.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_DROP_NOT_OWN_SHIFT"));
        perform(get("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId), otherOperator)
                .andExpect(status().isNotFound());
        perform(get("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId), setup.operator())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void personWhoRecordedADropCannotConfirmIt() throws Exception {
        long dropId = recordedDropId(setup.manager(), "300.00");

        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.manager())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_DROP_SELF_CONFIRMATION"));
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.owner())
                .andExpect(status().isOk());
    }

    @Test
    void resentDropWithTheSameKeyIsRecordedOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("amount", new BigDecimal("250.00"));

        String first = perform(post("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId)
                .header("Idempotency-Key", key), setup.operator(), body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = perform(post("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId)
                .header("Idempotency-Key", key), setup.operator(), body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));
        assertThat(dropRepository.findByShiftIdOrderByCreatedAtDescIdDesc(shiftId)).hasSize(1);
    }

    @Test
    void closeIsRefusedWhileADropWaitsForConfirmation() throws Exception {
        recordedDropId(setup.operator(), "600.00");

        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), closing("400.00", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_DROPS_PENDING_AT_CLOSE"))
                .andExpect(jsonPath("$.params.count").value(1))
                .andExpect(jsonPath("$.params.amount").value("600.00"));
        assertThat(shiftRepository.findById(shiftId).orElseThrow().getStatus()).isEqualTo(ShiftStatus.OPEN);
    }

    @Test
    void closeComparesOnlyTheBagAfterConfirmedDropsAndPutsOnlyTheBagInTheSafe() throws Exception {
        long confirmed = recordedDropId(setup.operator(), "600.00");
        long rejected = recordedDropId(setup.operator(), "200.00");
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), confirmed), setup.manager())
                .andExpect(status().isOk());
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/reject", setup.pumpId(), rejected), setup.manager(),
                Map.of("reason", "Not received")).andExpect(status().isOk());

        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(),
                closing("400.00", notes(0, 2, 0, 0, 0, 0, "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_BALANCED"))
                .andExpect(jsonPath("$.cashCollected").value(1000.00))
                .andExpect(jsonPath("$.cashDropped").value(600.00))
                .andExpect(jsonPath("$.cashDenominations.notes200").value(2));

        assertThat(eventsOfType(CashEventType.CASH_IN)).singleElement()
                .satisfies(event -> assertThat(event.getAmount()).isEqualByComparingTo("400.00"));
        assertThat(safeBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void bagShortAfterDropsIsReportedAsAShortDiscrepancy() throws Exception {
        long dropId = recordedDropId(setup.operator(), "600.00");
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.manager())
                .andExpect(status().isOk());

        Map<String, Object> shortClose = closing("350.00", null);
        shortClose.put("discrepancyReason", "Bag counted short");
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), shortClose)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancyType").value("SHORT"))
                .andExpect(jsonPath("$.discrepancyAmount").value(50.00));
    }

    @Test
    void closeWhoseBreakdownDoesNotMatchTheCashIsRefused() throws Exception {
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(),
                closing("1000.00", notes(1, 0, 0, 0, 0, 0, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DENOMINATIONS_TOTAL_MISMATCH"));
        assertThat(shiftRepository.findById(shiftId).orElseThrow().getStatus()).isEqualTo(ShiftStatus.OPEN);
    }

    @Test
    void handoverCarriesTheBagCountAndAlsoWaitsForPendingDrops() throws Exception {
        User incoming = saveUser(UserRole.OPERATOR, setup.pumpId());
        long dropId = recordedDropId(setup.operator(), "500.00");
        Map<String, Object> handover = Map.of("outgoingShiftId", shiftId, "incomingOperatorId", incoming.getId(),
                "physicalCashVerified", true, "meterReadingsVerified", true,
                "closing", closing("500.00", notes(1, 0, 0, 0, 0, 0, "0")));

        perform(post("/api/pumps/{pumpId}/handovers", setup.pumpId()), setup.manager(), handover)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_DROPS_PENDING_AT_CLOSE"));
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.owner())
                .andExpect(status().isOk());
        perform(post("/api/pumps/{pumpId}/handovers", setup.pumpId()), setup.manager(), handover)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outgoingShiftStatus").value("CLOSED_BALANCED"));

        assertThat(shiftRepository.findById(shiftId).orElseThrow().getCashDenominations().notes500()).isEqualTo(1);
    }

    @Test
    void closedShiftTakesNoMoreDrops() throws Exception {
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), closing("1000.00", null))
                .andExpect(status().isOk());

        recordDrop(setup.operator(), Map.of("amount", new BigDecimal("100.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_DROP_SHIFT_NOT_OPEN"));
    }

    private ResultActions recordDrop(User user, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId), user, body);
    }

    private long recordedDropId(User user, String amount) throws Exception {
        JsonNode drop = json(recordDrop(user, Map.of("amount", new BigDecimal(amount))).andExpect(status().isCreated()));
        return drop.get("id").asLong();
    }

    private Map<String, Object> closing(String bagCash, Map<String, Object> denominations) {
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        request.put("cashCollected", new BigDecimal(bagCash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        if (denominations != null) {
            request.put("cashDenominations", denominations);
        }
        return request;
    }

    static Map<String, Object> notes(int n500, int n200, int n100, int n50, int n20, int n10, String coins) {
        return Map.of("notes500", n500, "notes200", n200, "notes100", n100, "notes50", n50, "notes20", n20, "notes10", n10,
                "coins", new BigDecimal(coins));
    }

    private BigDecimal safeBalance() {
        return cashEventRepository.computeBalance(setup.pumpId());
    }

    private List<CashEvent> eventsOfType(CashEventType type) {
        return cashEventRepository.findByPumpIdAndEventTypeOrderByEventDateDescCreatedAtDesc(setup.pumpId(), type, PageRequest.of(0, 20))
                .getContent();
    }
}
