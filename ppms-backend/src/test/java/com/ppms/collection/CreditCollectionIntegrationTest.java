package com.ppms.collection;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Credit held at 45 days overdue, reminders at 7/15/30 days, promises to pay, monthly statements and UPI links. */
class CreditCollectionIntegrationTest extends ApiIntegrationTest {

    private static final int MONTHLY_CYCLE_DAYS = 30;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CreditPromiseService promiseService;
    @Autowired private CreditFollowUpService followUpService;
    @Autowired private MonthlyStatementService statementService;

    private PumpSetup setup;
    private long shiftId;

    @BeforeEach
    void createPumpWithOpenShift() throws Exception {
        setup = createPumpWithStock("5000");
        shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    @Test
    void groupFortyFiveDaysOverdueIsOnHoldAndRefusedCreditAtTheShiftAndTheCounter() throws Exception {
        long parentId = createClient(null);
        long childId = createClient(parentId);
        makeOverdue(sellOnCredit(childId, "300.00"), 45);

        assertThat(standingOf(parentId)).isEqualTo("ON_HOLD");
        assertThat(standingOf(childId)).isEqualTo("ON_HOLD");
        trySellOnCredit(childId, "100.00")
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("CREDIT_ON_HOLD"))
                .andExpect(jsonPath("$.params.daysOverdue").value(45))
                .andExpect(jsonPath("$.params.holdDays").value(45));
        sellAtTheCounterOnCredit(parentId)
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("CREDIT_ON_HOLD"));
    }

    @Test
    void groupBelowTheHoldDaysIsOnlyOverdueAndAPumpWithTheHoldOffNeverHolds() throws Exception {
        long clientId = createClient(null);
        long entryId = sellOnCredit(clientId, "300.00");
        makeOverdue(entryId, 44);
        assertThat(standingOf(clientId)).isEqualTo("OVERDUE");

        makeOverdue(entryId, 90);
        saveSettings(setup.owner(), List.of(7, 15, 30), null, null, null).andExpect(status().isOk());

        assertThat(standingOf(clientId)).isEqualTo("OVERDUE");
        trySellOnCredit(clientId, "10.00").andExpect(jsonPath("$.code").value("CREDIT_BILLING_CYCLE_OVERDUE"));
    }

    @Test
    void cycleExtensionDoesNotLiftTheHoldButAnAuditedHoldOverrideDoes() throws Exception {
        long clientId = createClient(null);
        makeOverdue(sellOnCredit(clientId, "300.00"), 50);

        grantExtension(clientId, "OVERDUE_BLOCK_WAIVER", setup.owner()).andExpect(status().isCreated());
        assertThat(standingOf(clientId)).isEqualTo("ON_HOLD");
        grantExtension(clientId, "CREDIT_HOLD_OVERRIDE", setup.manager()).andExpect(status().isForbidden());

        grantExtension(clientId, "CREDIT_HOLD_OVERRIDE", setup.owner()).andExpect(status().isCreated());

        assertThat(standingOf(clientId)).isEqualTo("OK");
        trySellOnCredit(clientId, "50.00").andExpect(status().is2xxSuccessful());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action = 'CREDIT_EXTENSION_GRANTED'
                AND description LIKE 'CREDIT_HOLD_OVERRIDE for clientId=' || ? || '%reason=Fleet pays on Friday'""",
                Integer.class, setup.pumpId(), clientId)).isEqualTo(1);
    }

    @Test
    void tokenPaymentKeepsTheHoldAndPayingTheOldBillReleasesIt() throws Exception {
        long clientId = createClient(null);
        makeOverdue(sellOnCredit(clientId, "300.00"), 60);
        assertThat(standingOf(clientId)).isEqualTo("ON_HOLD");

        recordPayment(clientId, "100.00", TestDates.today());
        assertThat(standingOf(clientId)).isEqualTo("ON_HOLD");
        trySellOnCredit(clientId, "50.00").andExpect(jsonPath("$.code").value("CREDIT_ON_HOLD"));

        recordPayment(clientId, "200.00", TestDates.today());
        assertThat(standingOf(clientId)).isEqualTo("OK");
        trySellOnCredit(clientId, "50.00").andExpect(status().is2xxSuccessful());
    }

    @Test
    void eachStageIsDueOncePerEpisodeATokenPaymentKeepsTheEpisodeAndClearingTheBillsStartsANewOne() throws Exception {
        long clientId = createClient(null);
        long entryId = sellOnCredit(clientId, "300.00");
        makeOverdue(entryId, 10);

        JsonNode due = dueFor(clientId);
        assertThat(due.get("stageDays").asInt()).isEqualTo(7);
        assertThat(due.get("lastRemindedAt").isNull()).isTrue();
        JsonNode reminder = json(markReminded(clientId, "CALL", "Said he will come Monday")
                .andExpect(status().isCreated()));
        assertThat(reminder.get("channel").asText()).isEqualTo("MANUAL");
        assertThat(reminder.get("stageDays").asInt()).isEqualTo(7);
        assertThat(reminder.get("remindedByName").asText()).isEqualTo(setup.manager().getFullName());
        assertThat(dueOrNull(clientId)).isNull();

        passDays(entryId, 10);
        assertThat(dueFor(clientId).get("stageDays").asInt()).isEqualTo(15);
        assertThat(dueFor(clientId).get("lastReminderMethod").asText()).isEqualTo("CALL");
        markReminded(clientId, "IN_PERSON", null).andExpect(status().isCreated());
        assertThat(dueOrNull(clientId)).isNull();

        LocalDate episodeStart = TestDates.today().minusDays(19);
        assertThat(followUpOf(clientId).get("overdueSince").asText()).isEqualTo(episodeStart.toString());
        recordPayment(clientId, "50.00", TestDates.today().minusDays(12));
        assertThat(dueOrNull(clientId)).isNull();
        assertThat(followUpOf(clientId).get("daysOverdue").asLong()).isEqualTo(20);
        assertThat(followUpOf(clientId).get("overdueSince").asText()).isEqualTo(episodeStart.toString());

        recordPayment(clientId, "250.00", TestDates.today().minusDays(10));
        assertThat(followUpOf(clientId).get("daysOverdue").asLong()).isZero();
        makeOverdue(sellOnCredit(clientId, "100.00"), 8);

        assertThat(dueFor(clientId).get("stageDays").asInt()).isEqualTo(7);
        assertThat(dueFor(clientId).get("overdueSince").asText()).isEqualTo(TestDates.today().minusDays(7).toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action = 'CREDIT_REMINDER_RECORDED'",
                Integer.class, setup.pumpId())).isEqualTo(2);
    }

    @Test
    void clientThatIsNotOverdueCannotBeMarkedReminded() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "300.00");

        markReminded(clientId, "CALL", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_REMINDER_CLIENT_NOT_OVERDUE"));
    }

    @Test
    void promisePausesRemindersAndAMissedPromiseAlertsOnceAndResumesThem() throws Exception {
        long clientId = createClient(null);
        makeOverdue(sellOnCredit(clientId, "300.00"), 10);

        long promiseId = json(recordPromise(clientId, "200.00", TestDates.today().plusDays(3))
                .andExpect(status().isCreated())).get("id").asLong();
        assertThat(dueOrNull(clientId)).isNull();
        recordPromise(clientId, "100.00", TestDates.today().plusDays(5))
                .andExpect(jsonPath("$.code").value("CREDIT_PROMISE_ALREADY_OPEN"));

        jdbcTemplate.update("UPDATE credit_payment_promises SET promised_date = ?, recorded_on = ? WHERE id = ?",
                TestDates.today().minusDays(1), TestDates.today().minusDays(4), promiseId);

        assertThat(promiseService.resolvePromises(setup.pumpId())).isEqualTo(1);
        promiseService.resolvePromises(setup.pumpId());

        assertThat(jdbcTemplate.queryForMap("SELECT status, paid_amount FROM credit_payment_promises WHERE id = ?", promiseId))
                .containsEntry("status", "MISSED")
                .satisfies(row -> assertThat((BigDecimal) row.get("paid_amount")).isEqualByComparingTo("0.00"));
        assertThat(notificationCount("CREDIT_PROMISE_MISSED")).isEqualTo(1);
        assertThat(dueFor(clientId).get("stageDays").asInt()).isEqualTo(7);
        JsonNode followUp = followUpOf(clientId);
        assertThat(followUp.get("promises").get(0).get("status").asText()).isEqualTo("MISSED");
        assertThat(followUp.get("reminderDue").asBoolean()).isTrue();
    }

    @Test
    void promiseIsKeptOnceThePromisedAmountIsPaidAndAPastDateIsRefused() throws Exception {
        long clientId = createClient(null);
        makeOverdue(sellOnCredit(clientId, "300.00"), 10);
        recordPromise(clientId, "100.00", TestDates.today().minusDays(1))
                .andExpect(jsonPath("$.code").value("CREDIT_PROMISE_DATE_IN_PAST"));
        long promiseId = json(recordPromise(clientId, "100.00", TestDates.today().plusDays(2))
                .andExpect(status().isCreated())).get("id").asLong();

        recordPayment(clientId, "150.00", TestDates.today());
        promiseService.resolvePromises(setup.pumpId());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM credit_payment_promises WHERE id = ?",
                String.class, promiseId)).isEqualTo("KEPT");
        assertThat(notificationCount("CREDIT_PROMISE_MISSED")).isZero();
    }

    @Test
    void dailyFollowUpNotifiesManagersOfTheDueListAndOfEachHoldOnce() throws Exception {
        makeOverdue(sellOnCredit(createClient(null), "300.00"), 10);
        makeOverdue(sellOnCredit(createClient(null), "400.00"), 50);

        followUpService.notifyDailyFollowUp(setup.pumpId());
        followUpService.notifyDailyFollowUp(setup.pumpId());

        assertThat(notificationCount("CREDIT_REMINDERS_DUE")).isEqualTo(1);
        assertThat(notificationCount("CREDIT_HOLD_PLACED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT params->>'count' FROM notifications WHERE pump_id = ? AND type = 'CREDIT_REMINDERS_DUE'",
                String.class, setup.pumpId())).isEqualTo("2");
    }

    @Test
    void monthlyStatementIsStoredOncePerClientAndMonthWithAUpiQrCode() throws Exception {
        saveSettings(setup.owner(), List.of(7, 15, 30), 45, "ramfuels@okaxis", "Ram Fuels").andExpect(status().isOk());
        long clientId = createClient(null);
        long idleClientId = createClient(null);
        YearMonth lastMonth = YearMonth.from(TestDates.today()).minusMonths(1);
        long entryId = sellOnCredit(clientId, "300.00");
        sellOnCredit(clientId, "70.00");
        jdbcTemplate.update("UPDATE shift_credit_entries SET sale_date = ? WHERE id = ?", lastMonth.atDay(10), entryId);

        MonthlyStatementService.GenerationResult first = statementService.generateForPump(setup.pumpId(), lastMonth);
        MonthlyStatementService.GenerationResult second = statementService.generateForPump(setup.pumpId(), lastMonth);

        assertThat(first.written()).isEqualTo(1);
        assertThat(first.failedClientIds()).isEmpty();
        assertThat(second.written()).isZero();
        JsonNode statements = json(perform(get("/api/pumps/{pumpId}/collections/statements?clientId={clientId}",
                setup.pumpId(), clientId), setup.accountant()).andExpect(status().isOk()));
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).get("closingBalance").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(statements.get(0).get("periodFrom").asText()).isEqualTo(lastMonth.atDay(1).toString());
        assertThat(json(perform(get("/api/pumps/{pumpId}/collections/statements?clientId={clientId}",
                setup.pumpId(), idleClientId), setup.manager()).andExpect(status().isOk()))).isEmpty();
        perform(get("/api/pumps/{pumpId}/collections/statements/{id}", setup.pumpId(), statements.get(0).get("id").asLong()),
                setup.manager())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("300.00 with any UPI app")))
                .andExpect(content().string(containsString("<svg")))
                .andExpect(content().string(containsString("ramfuels@okaxis")));
        assertThat(json(perform(get("/api/pumps/{pumpId}/collections/statements?month={month}", setup.pumpId(),
                lastMonth.toString()), setup.manager()).andExpect(status().isOk()))).hasSize(1);
    }

    @Test
    void settingsAreValidatedAndOnlyOwnersAndAdminsChangeThem() throws Exception {
        saveSettings(setup.owner(), List.of(7, 15), 45, "not-a-upi-id", "Ram Fuels")
                .andExpect(jsonPath("$.code").value("UPI_ID_INVALID"));
        saveSettings(setup.owner(), List.of(7, 15), 45, "ramfuels@okaxis", " ")
                .andExpect(jsonPath("$.code").value("UPI_DETAILS_INCOMPLETE"));
        saveSettings(setup.owner(), List.of(7, 15), 45, "ramfuels@okaxis", "Ram?pa=x")
                .andExpect(jsonPath("$.code").value("UPI_PAYEE_NAME_INVALID"));
        saveSettings(setup.owner(), List.of(7, 7), 45, null, null)
                .andExpect(jsonPath("$.code").value("REMINDER_STAGES_INVALID"));
        saveSettings(setup.owner(), List.of(0), 45, null, null)
                .andExpect(jsonPath("$.code").value("REMINDER_STAGES_INVALID"));
        saveSettings(setup.owner(), List.of(7), 0, null, null)
                .andExpect(jsonPath("$.code").value("CREDIT_HOLD_DAYS_OUT_OF_RANGE"));
        saveSettings(setup.manager(), List.of(7), 45, null, null).andExpect(status().isForbidden());

        saveSettings(setup.admin(), List.of(30, 10), 60, " ramfuels@okaxis ", "Ram Fuels").andExpect(status().isOk());

        JsonNode saved = json(perform(get("/api/pumps/{pumpId}/collection-settings", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        assertThat(saved.get("reminderStageDays").toString()).isEqualTo("[10,30]");
        assertThat(saved.get("creditHoldDays").asInt()).isEqualTo(60);
        assertThat(saved.get("upiId").asText()).isEqualTo("ramfuels@okaxis");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action = 'COLLECTION_SETTINGS_UPDATED'",
                Integer.class, setup.pumpId())).isEqualTo(1);
    }

    @Test
    void dashboardShowsOverdueAgeCollectedPromisesHoldsAndTodaysReminders() throws Exception {
        long promisedId = createClient(null);
        long heldId = createClient(null);
        long payingId = createClient(null);
        makeOverdue(sellOnCredit(promisedId, "300.00"), 10);
        makeOverdue(sellOnCredit(heldId, "500.00"), 50);
        sellOnCredit(payingId, "200.00");
        recordPayment(payingId, "120.00", TestDates.today());
        recordPromise(promisedId, "300.00", TestDates.today()).andExpect(status().isCreated());

        JsonNode dashboard = json(perform(get("/api/pumps/{pumpId}/collections/dashboard", setup.pumpId()),
                setup.accountant()).andExpect(status().isOk()));

        assertThat(dashboard.get("totalOverdue").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(dashboard.get("totalOutstanding").decimalValue()).isEqualByComparingTo("880.00");
        assertThat(dashboard.get("overdueClientCount").asInt()).isEqualTo(2);
        assertThat(dashboard.get("ageBuckets").get("days61To90").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(dashboard.get("collectedThisMonth").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(dashboard.get("promisesDueToday").get(0).get("clientId").asLong()).isEqualTo(promisedId);
        assertThat(dashboard.get("clientsOnHold")).hasSize(1);
        assertThat(dashboard.get("clientsOnHold").get(0).get("clientId").asLong()).isEqualTo(heldId);
        assertThat(dashboard.get("topOverdue").get(0).get("clientId").asLong()).isEqualTo(heldId);
        assertThat(dashboard.get("toRemind")).hasSize(1);
        assertThat(dashboard.get("toRemind").get(0).get("clientId").asLong()).isEqualTo(heldId);
        assertThat(dashboard.get("toRemind").get(0).get("stageDays").asInt()).isEqualTo(30);
        assertThat(dashboard.get("toRemind").get(0).get("onHold").asBoolean()).isTrue();
    }

    @Test
    void portalAndReminderListCarryAUpiLinkForTheExactOutstanding() throws Exception {
        saveSettings(setup.owner(), List.of(7), 45, "ramfuels@okaxis", "Ram Fuels").andExpect(status().isOk());
        String phone = uniquePhone();
        long clientId = createClient(null, phone);
        makeOverdue(sellOnCredit(clientId, "345.50"), 8);
        perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId), setup.owner(),
                Map.of("pin", "482913")).andExpect(status().isNoContent());

        JsonNode balance = json(mockMvc.perform(post("/api/public/credit-balance")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("pumpId", setup.pumpId(), "phone", phone, "pin", "482913"))))
                .andExpect(status().isOk()));

        assertThat(balance.get("upiPayLink").asText()).startsWith("upi://pay?pa=ramfuels@okaxis&pn=Ram%20Fuels&am=345.50&cu=INR");
        assertThat(dueFor(clientId).get("upiPayLink").asText()).contains("am=345.50");
    }

    @Test
    void promiseIsCappedAtNinetyDaysAheadAndAtWhatTheGroupOwes() throws Exception {
        long parentId = createClient(null);
        long childId = createClient(parentId);
        sellOnCredit(parentId, "200.00");
        sellOnCredit(childId, "100.00");

        recordPromise(childId, "100.00", TestDates.today().plusDays(91))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PROMISE_DATE_TOO_FAR"))
                .andExpect(jsonPath("$.params.maxDays").value(90));
        recordPromise(childId, "300.01", TestDates.today().plusDays(5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PROMISE_EXCEEDS_OUTSTANDING"))
                .andExpect(jsonPath("$.params.outstanding").value("300.00"));
        JsonNode promise = json(recordPromise(childId, "300.00", TestDates.today().plusDays(90))
                .andExpect(status().isCreated()));

        assertThat(promise.get("clientId").asLong()).isEqualTo(parentId);
    }

    @Test
    void openPromiseCanBeEditedAndCancelledWithHistoryAndAuditAndThenNoLonger() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "300.00");
        long promiseId = json(recordPromise(clientId, "200.00", TestDates.today().plusDays(3))
                .andExpect(status().isCreated())).get("id").asLong();

        editPromise(promiseId, "400.00", TestDates.today().plusDays(10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PROMISE_EXCEEDS_OUTSTANDING"));
        JsonNode edited = json(editPromise(promiseId, "250.00", TestDates.today().plusDays(10))
                .andExpect(status().isOk()));
        assertThat(edited.get("amount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(edited.get("promisedDate").asText()).isEqualTo(TestDates.today().plusDays(10).toString());

        perform(post("/api/pumps/{pumpId}/collections/promises/{id}/cancel", setup.pumpId(), promiseId), setup.manager(),
                Map.of("reason", " ")).andExpect(status().isBadRequest());
        JsonNode cancelled = json(perform(post("/api/pumps/{pumpId}/collections/promises/{id}/cancel", setup.pumpId(),
                promiseId), setup.manager(), Map.of("reason", "Client disputes the bill")).andExpect(status().isOk()));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");

        editPromise(promiseId, "100.00", TestDates.today().plusDays(4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PROMISE_NOT_OPEN"));
        assertThat(jdbcTemplate.queryForList(
                "SELECT change_type FROM credit_payment_promise_changes WHERE promise_id = ? ORDER BY id", String.class,
                promiseId)).containsExactly("EDITED", "CANCELLED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT action::text FROM audit_logs WHERE pump_id = ? AND entity_id = ?"
                        + " AND entity_type = 'CreditPaymentPromise' ORDER BY id", String.class,
                setup.pumpId(), Long.toString(promiseId)))
                .containsExactly("CREDIT_PROMISE_RECORDED", "CREDIT_PROMISE_EDITED", "CREDIT_PROMISE_CANCELLED");
        recordPromise(clientId, "100.00", TestDates.today().plusDays(2)).andExpect(status().isCreated());
    }

    @Test
    void promiseOfAnotherPumpCannotBeChanged() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "300.00");
        long promiseId = json(recordPromise(clientId, "200.00", TestDates.today().plusDays(3))
                .andExpect(status().isCreated())).get("id").asLong();
        PumpSetup otherPump = createPumpWithStock("0");

        perform(post("/api/pumps/{pumpId}/collections/promises/{id}/cancel", otherPump.pumpId(), promiseId),
                otherPump.owner(), Map.of("reason", "Not ours")).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM credit_payment_promises WHERE id = ?",
                String.class, promiseId)).isEqualTo("OPEN");
    }

    @Test
    void promiseIsKeptOnceTheAccountOwesNothingEvenIfPaidBeforeItWasRecorded() throws Exception {
        long clientId = createClient(null);
        sellOnCredit(clientId, "300.00");
        long promiseId = json(recordPromise(clientId, "300.00", TestDates.today().plusDays(3))
                .andExpect(status().isCreated())).get("id").asLong();
        jdbcTemplate.update("UPDATE credit_payment_promises SET recorded_on = ? WHERE id = ?",
                TestDates.today().plusDays(1), promiseId);
        recordPayment(clientId, "300.00", TestDates.today());

        promiseService.resolvePromises(setup.pumpId());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM credit_payment_promises WHERE id = ?",
                String.class, promiseId)).isEqualTo("KEPT");
    }

    @Test
    void paymentAwaitingApprovalKeepsAPassedPromiseOpenUntilItIsRejected() throws Exception {
        long clientId = createClient(null);
        makeOverdue(sellOnCredit(clientId, "300.00"), 10);
        long promiseId = json(recordPromise(clientId, "200.00", TestDates.today().plusDays(3))
                .andExpect(status().isCreated())).get("id").asLong();
        recordPayment(clientId, "50.00", TestDates.today());
        long pendingId = savePaymentRow(clientId, "150.00", TestDates.today(), "PENDING_APPROVAL");
        jdbcTemplate.update("UPDATE credit_payment_promises SET promised_date = ?, recorded_on = ? WHERE id = ?",
                TestDates.today().minusDays(1), TestDates.today().minusDays(1), promiseId);
        jdbcTemplate.update("UPDATE credit_payments SET payment_date = payment_date - 1 WHERE client_id = ?", clientId);

        assertThat(promiseService.resolvePromises(setup.pumpId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM credit_payment_promises WHERE id = ?",
                String.class, promiseId)).isEqualTo("OPEN");
        assertThat(notificationCount("CREDIT_PROMISE_MISSED")).isZero();

        jdbcTemplate.update("UPDATE credit_payments SET payment_approval_status = 'REJECTED' WHERE id = ?", pendingId);
        assertThat(promiseService.resolvePromises(setup.pumpId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT status, paid_amount FROM credit_payment_promises WHERE id = ?", promiseId))
                .containsEntry("status", "MISSED")
                .satisfies(row -> assertThat((BigDecimal) row.get("paid_amount")).isEqualByComparingTo("50.00"));
    }

    @Test
    void inactiveAccountGetsAMonthlyStatementOnlyWhileItStillOwes() throws Exception {
        long owingId = createClient(null);
        long settledId = createClient(null);
        YearMonth lastMonth = YearMonth.from(TestDates.today()).minusMonths(1);
        jdbcTemplate.update("UPDATE shift_credit_entries SET sale_date = ? WHERE id = ?", lastMonth.atDay(10),
                sellOnCredit(owingId, "300.00"));
        jdbcTemplate.update("UPDATE shift_credit_entries SET sale_date = ? WHERE id = ?", lastMonth.atDay(10),
                sellOnCredit(settledId, "200.00"));
        savePaymentRow(settledId, "200.00", lastMonth.atDay(20), "APPROVED");
        jdbcTemplate.update("UPDATE credit_clients SET is_active = FALSE WHERE id IN (?, ?)", owingId, settledId);

        MonthlyStatementService.GenerationResult result = statementService.generateForPump(setup.pumpId(), lastMonth);

        assertThat(result.written()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT client_id FROM credit_monthly_statements WHERE pump_id = ?",
                Long.class, setup.pumpId())).containsExactly(owingId);
    }

    private ResultActions editPromise(long promiseId, String amount, LocalDate promisedDate) throws Exception {
        return perform(put("/api/pumps/{pumpId}/collections/promises/{id}", setup.pumpId(), promiseId), setup.manager(),
                Map.of("amount", new BigDecimal(amount), "promisedDate", promisedDate.toString(),
                        "reason", "Client asked for more time"));
    }

    private long savePaymentRow(long clientId, String amount, LocalDate paidAt, String approvalStatus) {
        return jdbcTemplate.queryForObject("INSERT INTO credit_payments (pump_id, client_id, amount, payment_mode, "
                        + "payment_date, received_by_user_id, payment_approval_status) VALUES (?, ?, ?, 'CASH', ?, ?, "
                        + "?::expense_approval_status) RETURNING id",
                Long.class, setup.pumpId(), clientId, new BigDecimal(amount), paidAt, setup.manager().getId(),
                approvalStatus);
    }

    private String standingOf(long clientId) throws Exception {
        JsonNode standings = json(perform(get("/api/pumps/{pumpId}/credit-clients/credit-standing", setup.pumpId()),
                setup.operator()).andExpect(status().isOk()));
        for (JsonNode standing : standings) {
            if (standing.get("clientId").asLong() == clientId) {
                return standing.get("creditStatus").asText();
            }
        }
        throw new AssertionError("Client " + clientId + " missing from the credit standing");
    }

    private JsonNode dueFor(long clientId) throws Exception {
        JsonNode due = dueOrNull(clientId);
        assertThat(due).as("client %d in the due list", clientId).isNotNull();
        return due;
    }

    private JsonNode dueOrNull(long clientId) throws Exception {
        JsonNode due = json(perform(get("/api/pumps/{pumpId}/collections/reminders/due", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        for (JsonNode row : due) {
            if (row.get("clientId").asLong() == clientId) {
                return row;
            }
        }
        return null;
    }

    private JsonNode followUpOf(long clientId) throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/collections/clients/{clientId}", setup.pumpId(), clientId),
                setup.manager()).andExpect(status().isOk()));
    }

    private ResultActions markReminded(long clientId, String method, String note) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("method", method);
        body.put("note", note);
        return perform(post("/api/pumps/{pumpId}/collections/clients/{clientId}/reminders", setup.pumpId(), clientId),
                setup.manager(), body);
    }

    private ResultActions recordPromise(long clientId, String amount, LocalDate promisedDate) throws Exception {
        return perform(post("/api/pumps/{pumpId}/collections/clients/{clientId}/promises", setup.pumpId(), clientId),
                setup.manager(), Map.of("amount", new BigDecimal(amount), "promisedDate", promisedDate.toString()));
    }

    private ResultActions saveSettings(User user, List<Integer> stages, Integer holdDays, String upiId, String payeeName)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reminderStageDays", stages);
        body.put("creditHoldDays", holdDays);
        body.put("upiId", upiId);
        body.put("upiPayeeName", payeeName);
        body.put("backdatedPaymentApprovalDays", 7);
        return perform(put("/api/pumps/{pumpId}/collection-settings", setup.pumpId()), user, body);
    }

    private ResultActions grantExtension(long clientId, String type, User user) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-clients/{clientId}/extensions", setup.pumpId(), clientId), user,
                Map.of("extensionType", type, "expiryDate", TestDates.today().plusDays(7).toString(),
                        "reason", "Fleet pays on Friday"));
    }

    private int notificationCount(String type) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications WHERE pump_id = ? AND type = ?::notification_type",
                Integer.class, setup.pumpId(), type);
    }

    private long createClient(Long parentClientId) throws Exception {
        return createClient(parentClientId, uniquePhone());
    }

    private long createClient(Long parentClientId, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", phone);
        body.put("parentClientId", parentClientId);
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private static String uniquePhone() {
        return "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L);
    }

    private long sellOnCredit(long clientId, String amount) throws Exception {
        JsonNode entries = json(trySellOnCredit(clientId, amount).andExpect(status().is2xxSuccessful())).get("creditEntries");
        return entries.get(entries.size() - 1).get("id").asLong();
    }

    private ResultActions trySellOnCredit(long clientId, String amount) throws Exception {
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), Map.of("clientName", "Client", "clientId", clientId,
                        "amount", new BigDecimal(amount), "fuelType", "PETROL"));
    }

    private ResultActions sellAtTheCounterOnCredit(long clientId) throws Exception {
        long productId = json(perform(post("/api/pumps/{pumpId}/ancillary/products", setup.pumpId()), setup.owner(),
                Map.of("name", "Engine Oil " + shortId(), "packageSize", new BigDecimal("1"), "unitOfMeasure", "LITRE",
                        "gstRatePercent", new BigDecimal("18")))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/prices", setup.pumpId(), productId), setup.owner(),
                Map.of("pricePerUnit", new BigDecimal("450.00"))).andExpect(status().is2xxSuccessful());
        perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/deliveries", setup.pumpId(), productId), setup.manager(),
                Map.of("quantityUnits", 5, "costPricePerUnit", new BigDecimal("300.00"),
                        "deliveryDate", TestDates.today().minusDays(2).toString(), "invoiceReference", "AN-" + shortId()))
                .andExpect(status().is2xxSuccessful());
        return perform(post("/api/pumps/{pumpId}/ancillary/sales", setup.pumpId()), setup.manager(),
                Map.of("productId", productId, "quantityUnits", 1, "sellingPricePerUnit", new BigDecimal("450.00"),
                        "paymentMode", "CREDIT", "clientId", clientId));
    }

    /** Dates the sale so a monthly-cycle client with nothing paid is this many days overdue today. */
    private void makeOverdue(long entryId, int daysOverdue) {
        int daysAgo = MONTHLY_CYCLE_DAYS + daysOverdue;
        jdbcTemplate.update("UPDATE shift_credit_entries SET created_at = ?, sale_date = ? WHERE id = ?",
                TestDates.now().minusDays(daysAgo), TestDates.today().minusDays(daysAgo), entryId);
    }

    /** Moves the sale and every reminder of the pump back, as if the days had passed. */
    private void passDays(long entryId, int days) {
        jdbcTemplate.update("UPDATE shift_credit_entries SET created_at = created_at - make_interval(days => ?), "
                + "sale_date = sale_date - ? WHERE id = ?", days, days, entryId);
        jdbcTemplate.update("UPDATE credit_reminders SET overdue_since = overdue_since - ? WHERE pump_id = ?",
                days, setup.pumpId());
    }

    private void recordPayment(long clientId, String amount, LocalDate paidAt) throws Exception {
        perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/payments", setup.pumpId(), clientId), setup.owner(),
                Map.of("amount", new BigDecimal(amount), "paymentMode", "CASH", "paidAt", paidAt.toString()))
                .andExpect(status().is2xxSuccessful());
    }
}
