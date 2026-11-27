package com.ppms.cash;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.notification.Notification;
import com.ppms.notification.NotificationRepository;
import com.ppms.notification.NotificationType;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The safe's balance and ledger, counts against it, and bank deposits out of it. */
class SafeIntegrationTest extends ApiIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

    @Autowired private NotificationRepository notificationRepository;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("5000");
    }

    @Test
    void balanceAddsShiftCashAndConfirmedDropsAndTakesOffDepositsAndCashOutsWithoutCountingAnythingTwice() throws Exception {
        long shiftId = openShift();
        long dropId = json(perform(post("/api/pumps/{pumpId}/shifts/{shiftId}/cash-drops", setup.pumpId(), shiftId),
                setup.operator(), Map.of("amount", new BigDecimal("700.00")))).get("id").asLong();
        perform(patch("/api/pumps/{pumpId}/cash-drops/{dropId}/confirm", setup.pumpId(), dropId), setup.manager())
                .andExpect(status().isOk());
        closeShift(shiftId, "300.00");
        recordDeposit("800.00", "SLIP-1").andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.manager(),
                Map.of("eventType", "CASH_OUT", "amount", new BigDecimal("50.00"), "description", "Tea and snacks",
                        "eventDate", TestDates.today().toString()))
                .andExpect(status().isCreated());

        perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150.00))
                .andExpect(jsonPath("$.pendingDropCount").value(0))
                .andExpect(jsonPath("$.countTolerance").value(10.00));

        JsonNode ledger = json(perform(get("/api/pumps/{pumpId}/safe/ledger", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        assertThat(ledger.get("totalElements").asInt()).isEqualTo(4);
        JsonNode newest = ledger.get("content").get(0);
        assertThat(newest.get("eventType").asText()).isEqualTo("CASH_OUT");
        assertThat(newest.get("amount").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(newest.get("balanceAfter").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(ledger.findValuesAsText("eventType")).containsExactly("CASH_OUT", "BANK_DEPOSIT", "CASH_IN", "CASH_DROP");
        assertThat(ledger.get("content").get(3).get("balanceAfter").decimalValue()).isEqualByComparingTo("700.00");
    }

    @Test
    void depositNeedsASlipReferenceAPastDateAndNoMoreThanTheSafeHolds() throws Exception {
        putCashInSafe("1000.00");

        recordDeposit("100.00", " ").andExpect(status().isBadRequest());
        recordDeposit(Map.of("amount", new BigDecimal("100.00"), "bankAccount", "SBI ••1234",
                "depositDate", TestDates.today().plusDays(1).toString(), "slipReference", "S-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_DEPOSIT_DATE_IN_FUTURE"));
        recordDeposit("1000.01", "S-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_DEPOSIT_EXCEEDS_SAFE_BALANCE"))
                .andExpect(jsonPath("$.params.balance").value("1000.00"));
        recordDeposit("400.00", "S-1").andExpect(status().isCreated());
        recordDeposit("100.00", "s-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_DEPOSIT_SLIP_REFERENCE_USED"));
    }

    @Test
    void backDatedDepositMustFitTheSafeOnItsDateAndLeaveNoLaterDayBelowZero() throws Exception {
        putCashInSafe("1000.00");
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.manager(),
                Map.of("eventType", "CASH_OUT", "amount", new BigDecimal("800.00"), "description", "Supplier paid",
                        "eventDate", TestDates.today().minusDays(1).toString()))
                .andExpect(status().isCreated());

        recordDeposit(deposit("100.00", "B-0", "SBI ••1234", TestDates.today().minusDays(6)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_DEPOSIT_EXCEEDS_SAFE_BALANCE"))
                .andExpect(jsonPath("$.params.balance").value("0.00"));
        recordDeposit(deposit("500.00", "B-1", "SBI ••1234", TestDates.today().minusDays(3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_DEPOSIT_LEAVES_SAFE_NEGATIVE"))
                .andExpect(jsonPath("$.params.lowestBalance").value("200.00"));
        recordDeposit(deposit("200.00", "B-2", "SBI ••1234", TestDates.today().minusDays(3)))
                .andExpect(status().isCreated());
        recordDeposit(deposit("0.01", "B-3", "SBI ••1234", TestDates.today()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BANK_DEPOSIT_EXCEEDS_SAFE_BALANCE"));
    }

    @Test
    void depositListFiltersByDateAndAccountAndTotalsEveryMatch() throws Exception {
        putCashInSafe("1000.00");
        recordDeposit(deposit("100.00", "A-1", "SBI ••1234", TestDates.today().minusDays(3))).andExpect(status().isCreated());
        recordDeposit(deposit("200.00", "A-2", "HDFC ••9876", TestDates.today())).andExpect(status().isCreated());
        recordDeposit(deposit("300.00", "A-3", "SBI ••1234", TestDates.today())).andExpect(status().isCreated());

        perform(get("/api/pumps/{pumpId}/safe/deposits", setup.pumpId()).param("bankAccount", "sbi").param("size", "1"),
                setup.manager())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.totalAmount").value(400.00))
                .andExpect(jsonPath("$.deposits.content.length()").value(1))
                .andExpect(jsonPath("$.deposits.content[0].slipReference").value("A-3"));
        perform(get("/api/pumps/{pumpId}/safe/deposits", setup.pumpId())
                        .param("from", TestDates.today().toString()).param("to", TestDates.today().toString()),
                setup.manager())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.totalAmount").value(500.00));
    }

    @Test
    void slipPhotoIsCheckedByItsContentAndServedBack() throws Exception {
        putCashInSafe("500.00");
        long depositId = json(recordDeposit("100.00", "PH-1")).get("id").asLong();

        attachSlip(depositId, new MockMultipartFile("file", "slip.png", "image/png", "<svg/>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEPOSIT_SLIP_TYPE_NOT_ALLOWED"));
        attachSlip(depositId, new MockMultipartFile("file", "slip.png", "image/png", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEPOSIT_SLIP_EMPTY"));
        attachSlip(depositId, new MockMultipartFile("file", "slip.png", "application/octet-stream", PNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasSlipPhoto").value(true));

        perform(get("/api/pumps/{pumpId}/safe/deposits/{depositId}/slip", setup.pumpId(), depositId), setup.owner())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(PNG));
        perform(get("/api/pumps/{pumpId}/safe/deposits/{depositId}/slip", setup.pumpId(), depositId), setup.operator())
                .andExpect(status().isForbidden());
    }

    @Test
    void countWithinToleranceIsQuietAndOneBeyondItNotifiesTheOwner() throws Exception {
        putCashInSafe("1000.00");

        recordCount(Map.of("countedAmount", new BigDecimal("995.00"), "denominations",
                CashDropIntegrationTest.notes(1, 2, 0, 1, 2, 0, "5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expectedAmount").value(1000.00))
                .andExpect(jsonPath("$.varianceAmount").value(-5.00))
                .andExpect(jsonPath("$.varianceType").value("SHORT"))
                .andExpect(jsonPath("$.beyondTolerance").value(false));
        assertThat(varianceNotifications()).isEmpty();

        recordCount(Map.of("countedAmount", new BigDecimal("1020.00")))
                .andExpect(jsonPath("$.varianceType").value("OVER"))
                .andExpect(jsonPath("$.beyondTolerance").value(true));
        assertThat(varianceNotifications()).singleElement()
                .satisfies(notification -> assertThat(notification.getMessage()).contains("OVER by ₹20.00"));

        perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager())
                .andExpect(jsonPath("$.latestCount.countedAmount").value(1020.00));
        perform(get("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void countBreakdownMustAddUpAndToleranceIsEditableByTheOwner() throws Exception {
        recordCount(Map.of("countedAmount", new BigDecimal("100.00"), "denominations",
                CashDropIntegrationTest.notes(0, 0, 0, 0, 0, 0, "99")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DENOMINATIONS_TOTAL_MISMATCH"));

        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(), Map.of("safeCountTolerance", 10001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAFE_COUNT_TOLERANCE_OUT_OF_RANGE"));
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(), Map.of("safeCountTolerance", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safeCountTolerance").value(0.00));

        recordCount(Map.of("countedAmount", new BigDecimal("0.01"))).andExpect(jsonPath("$.beyondTolerance").value(true));
    }

    @Test
    void dropsAndDepositsCannotBeTypedInAsManualCashMovements() throws Exception {
        for (String type : List.of("CASH_DROP", "BANK_DEPOSIT")) {
            perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.manager(),
                    Map.of("eventType", type, "amount", new BigDecimal("10.00"), "description", "By hand",
                            "eventDate", TestDates.today().toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CASH_EVENT_TYPE_NOT_MANUAL"));
        }
    }

    @Test
    void safeIsForManagersAdminsAndOwnersOnly() throws Exception {
        perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.operator()).andExpect(status().isForbidden());
        perform(get("/api/pumps/{pumpId}/safe/ledger", setup.pumpId()), setup.accountant()).andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.operator(),
                Map.of("countedAmount", BigDecimal.ONE)).andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/safe/deposits", setup.pumpId()), setup.operator(),
                deposit("1.00", "X", "SBI", TestDates.today())).andExpect(status().isForbidden());
    }

    private void putCashInSafe(String amount) throws Exception {
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.manager(),
                Map.of("eventType", "CASH_IN", "amount", new BigDecimal(amount), "description", "Float",
                        "eventDate", TestDates.today().minusDays(5).toString()))
                .andExpect(status().isCreated());
    }

    private ResultActions recordDeposit(String amount, String slipReference) throws Exception {
        return recordDeposit(deposit(amount, slipReference, "SBI current ••1234", TestDates.today()));
    }

    private ResultActions recordDeposit(Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/safe/deposits", setup.pumpId()), setup.manager(), body);
    }

    private Map<String, Object> deposit(String amount, String slipReference, String account, LocalDate date) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("bankAccount", account);
        body.put("depositDate", date.toString());
        body.put("slipReference", slipReference);
        return body;
    }

    private ResultActions attachSlip(long depositId, MockMultipartFile file) throws Exception {
        return perform(multipart(HttpMethod.PUT, "/api/pumps/{pumpId}/safe/deposits/{depositId}/slip", setup.pumpId(), depositId)
                .file(file), setup.manager());
    }

    private ResultActions recordCount(Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(), body);
    }

    private List<Notification> varianceNotifications() {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getPumpId().equals(setup.pumpId())
                        && notification.getType() == NotificationType.SAFE_COUNT_VARIANCE)
                .toList();
    }

    private long openShift() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private void closeShift(long shiftId, String bagCash) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        request.put("cashCollected", new BigDecimal(bagCash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));
    }
}
