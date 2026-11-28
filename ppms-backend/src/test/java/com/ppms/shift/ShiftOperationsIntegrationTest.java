package com.ppms.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.fuel.FuelType;
import com.ppms.pump.Nozzle;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Several operators on one DU shift, nozzle swaps and relief breaks: who sold which litres, without closing the shift. */
class ShiftOperationsIntegrationTest extends ApiIntegrationTest {

    private static final String SHIFT_PATH = "/api/pumps/{pumpId}/shifts/{id}";

    @Autowired private ShiftOperatorRepository shiftOperatorRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private Nozzle secondNozzle;
    private User second;
    private User reliever;

    @BeforeEach
    void createPumpWithTwoNozzles() throws Exception {
        setup = createPumpWithStock("5000");
        secondNozzle = nozzleRepository.save(Nozzle.builder()
                .duId(setup.du().getId())
                .nozzleNumber(2)
                .fuelType(FuelType.PETROL)
                .lastReading(new BigDecimal("2000.000"))
                .tankId(setup.tank().getId())
                .build());
        second = saveUser(UserRole.OPERATOR, setup.pumpId());
        reliever = saveUser(UserRole.OPERATOR, setup.pumpId());
    }

    @Test
    void secondOperatorAndSwappedNozzleSplitTheSalesAndTheCollections() throws Exception {
        long shiftId = openShift();
        JsonNode added = json(perform(post(SHIFT_PATH + "/operators", setup.pumpId(), shiftId), setup.manager(),
                Map.of("operatorId", second.getId())).andExpect(status().isCreated()));
        assertThat(added.get("otherOperators").get(0).get("operatorId").asLong()).isEqualTo(second.getId());
        assertThat(added.get("otherOperators").get(0).get("role").asText()).isEqualTo("ADDITIONAL");

        swap(shiftId, secondNozzle.getId(), second.getId(), "2005.000").andExpect(status().isCreated());

        perform(get(SHIFT_PATH, setup.pumpId(), shiftId), second).andExpect(status().isOk());
        JsonNode active = json(perform(get("/api/pumps/{pumpId}/shifts/active", setup.pumpId()), second).andExpect(status().isOk()));
        assertThat(active).hasSize(1);

        JsonNode closed = json(perform(post(SHIFT_PATH + "/close", setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1010.000", "2020.000", "3000.00", List.of(collection(setup.operator(), "1450.00"),
                        collection(second, "1550.00"))))
                .andExpect(status().isOk()));
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED_BALANCED");
        assertThat(closed.get("totalAmountDue").decimalValue()).isEqualByComparingTo("3000.00");

        JsonNode operations = operations(shiftId, setup.manager());
        JsonNode shares = operations.get("operatorShares");
        assertThat(shares).hasSize(2);
        JsonNode ownerShare = shareOf(shares, setup.operator());
        JsonNode secondShare = shareOf(shares, second);
        assertThat(ownerShare.get("litresSold").decimalValue()).isEqualByComparingTo("15.000");
        assertThat(ownerShare.get("amountDue").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(ownerShare.get("difference").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(secondShare.get("litresSold").decimalValue()).isEqualByComparingTo("15.000");
        assertThat(secondShare.get("difference").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(eventTypes(operations)).containsSubsequence("SHIFT_OPENED", "OPERATOR_JOINED", "NOZZLE_SWAPPED", "SHIFT_CLOSED");
        assertThat(shiftOperatorRepository.findByOperatorIdAndLeftAtIsNull(second.getId())).isEmpty();
        assertThat(auditCount(shiftId, "NOZZLE_SWAPPED")).isEqualTo(1);
        assertThat(auditCount(shiftId, "SHIFT_OPERATOR_ADDED")).isEqualTo(1);
    }

    @Test
    void reliefOperatorSellsDuringTheBreakAndTheOperatorTakesTheNozzlesBack() throws Exception {
        long shiftId = openShift();

        perform(post(SHIFT_PATH + "/reliefs", setup.pumpId(), shiftId), setup.operator(),
                relief(reliever, List.of(reading(setup.nozzle(), "1003.000"), reading(secondNozzle, "2002.000")),
                        new BigDecimal("200.00")))
                .andExpect(status().isCreated());
        JsonNode during = operations(shiftId, setup.operator());
        long reliefId = during.get("reliefs").get(0).get("id").asLong();
        assertThat(during.get("operators").get(0).get("onBreak").asBoolean()).isTrue();
        assertThat(during.get("operators").get(1).get("role").asText()).isEqualTo("RELIEF");
        assertThat(during.get("operators").get(1).get("nozzleIds")).hasSize(2);

        perform(post(SHIFT_PATH + "/reliefs/{reliefId}/end", setup.pumpId(), shiftId, reliefId), setup.operator(),
                Map.of("readings", List.of(reading(setup.nozzle(), "1008.000"), reading(secondNozzle, "2002.000")),
                        "cashHandedBack", new BigDecimal("700.00")))
                .andExpect(status().isOk());
        assertThat(shiftOperatorRepository.findByOperatorIdAndLeftAtIsNull(reliever.getId())).isEmpty();

        perform(post(SHIFT_PATH + "/close", setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1010.000", "2002.000", "1200.00", List.of())).andExpect(status().isOk());

        JsonNode operations = operations(shiftId, setup.manager());
        assertThat(shareOf(operations.get("operatorShares"), setup.operator()).get("litresSold").decimalValue())
                .isEqualByComparingTo("7.000");
        assertThat(shareOf(operations.get("operatorShares"), reliever).get("litresSold").decimalValue())
                .isEqualByComparingTo("5.000");
        assertThat(shareOf(operations.get("operatorShares"), reliever).get("collected").isNull()).isTrue();
        List<String> types = eventTypes(operations);
        assertThat(types).containsSubsequence("SHIFT_OPENED", "RELIEF_STARTED", "RELIEF_ENDED", "SHIFT_CLOSED");
        JsonNode started = operations.get("timeline").get(types.indexOf("RELIEF_STARTED"));
        assertThat(started.get("amount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(started.get("otherOperatorName").asText()).isEqualTo(reliever.getFullName());
        assertThat(started.get("readings")).hasSize(2);
        assertThat(auditCount(shiftId, "RELIEF_STARTED")).isEqualTo(1);
        assertThat(auditCount(shiftId, "RELIEF_ENDED")).isEqualTo(1);
        perform(get(SHIFT_PATH + "/operations", setup.pumpId(), shiftId), second).andExpect(status().isNotFound());
    }

    private long openShift() throws Exception {
        JsonNode opened = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId(), secondNozzle.getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().isCreated()));
        return opened.get("id").asLong();
    }

    private ResultActions swap(long shiftId, Long nozzleId, Long toOperatorId, String reading) throws Exception {
        return perform(post(SHIFT_PATH + "/nozzle-swaps", setup.pumpId(), shiftId), setup.manager(),
                Map.of("nozzleId", nozzleId, "toOperatorId", toOperatorId, "meterReading", new BigDecimal(reading)));
    }

    private JsonNode operations(long shiftId, User user) throws Exception {
        return json(perform(get(SHIFT_PATH + "/operations", setup.pumpId(), shiftId), user).andExpect(status().isOk()));
    }

    private Map<String, Object> relief(User reliefOperator, List<Map<String, Object>> readings, BigDecimal cashHandedOver) {
        Map<String, Object> body = new HashMap<>();
        body.put("relievedOperatorId", setup.operator().getId());
        body.put("reliefOperatorId", reliefOperator.getId());
        body.put("readings", readings);
        body.put("cashHandedOver", cashHandedOver);
        return body;
    }

    private static Map<String, Object> reading(Nozzle nozzle, String meterReading) {
        return Map.of("nozzleId", nozzle.getId(), "meterReading", new BigDecimal(meterReading));
    }

    private static Map<String, Object> collection(User operator, String cash) {
        return Map.of("operatorId", operator.getId(), "cashCollected", new BigDecimal(cash), "upiCollected", BigDecimal.ZERO,
                "cardCollected", BigDecimal.ZERO, "fleetCardCollected", BigDecimal.ZERO, "creditTotal", BigDecimal.ZERO);
    }

    private Map<String, Object> closeRequest(String firstEnd, String secondEnd, String cash,
                                             List<Map<String, Object>> operatorCollections) {
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(
                Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal(firstEnd)),
                Map.of("nozzleId", secondNozzle.getId(), "endReading", new BigDecimal(secondEnd))));
        request.put("cashCollected", new BigDecimal(cash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        request.put("operatorCollections", operatorCollections);
        return request;
    }

    private static JsonNode shareOf(JsonNode shares, User operator) {
        for (JsonNode share : shares) {
            if (share.get("operatorId").asLong() == operator.getId()) {
                return share;
            }
        }
        throw new AssertionError("No share for operator " + operator.getId());
    }

    private static List<String> eventTypes(JsonNode operations) {
        List<String> types = new ArrayList<>();
        operations.get("timeline").forEach(event -> types.add(event.get("type").asText()));
        return types;
    }

    private int auditCount(long shiftId, String action) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action::text = ?",
                Integer.class, String.valueOf(shiftId), action);
    }
}
