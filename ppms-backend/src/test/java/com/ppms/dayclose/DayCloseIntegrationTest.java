package com.ppms.dayclose;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditLog;
import com.ppms.audit.AuditLogRepository;
import com.ppms.cash.SafeCount;
import com.ppms.cash.SafeCountRepository;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.notification.Notification;
import com.ppms.notification.NotificationRepository;
import com.ppms.notification.NotificationType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Closing a day: the blocking checks, the stored summary, owner approval and questions, re-opening and versions. */
class DayCloseIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired private SafeCountRepository safeCountRepository;
    @Autowired private GlobalFuelPriceRepository fuelPriceRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("5000");
    }

    @Test
    void closingIsRefusedWithoutASafeCountThatDay() throws Exception {
        perform(get("/api/pumps/{pumpId}/day-closes/preview", setup.pumpId()).param("date", TODAY.toString()), setup.manager())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.checks[0].code").value("SAFE_COUNT"))
                .andExpect(jsonPath("$.checks[0].done").value(false))
                .andExpect(jsonPath("$.checks[0].fixPath").value("/dashboard/cash?tab=safe&action=count-safe&countFor=" + TODAY))
                .andExpect(jsonPath("$.checks[1].code").value("NEXT_DAY_PRICES"))
                .andExpect(jsonPath("$.checks[1].done").value(true))
                .andExpect(jsonPath("$.summary.cash.flag.colour").value("RED"));

        closeDay(TODAY, setup.manager())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSE_SAFE_COUNT_MISSING"))
                .andExpect(jsonPath("$.params.date").value(TODAY.toString()))
                .andExpect(jsonPath("$.params.missingCodes[0]").value("SAFE_COUNT"));
    }

    @Test
    void closingIsRefusedUntilTheNextDayPricesAreSetOrConfirmed() throws Exception {
        safeCountOn(YESTERDAY);
        for (GlobalFuelPrice price : fuelPriceRepository.findCurrentPricesForPump(setup.pumpId())) {
            price.setEffectiveFrom(TestDates.now().minusDays(2));
            fuelPriceRepository.save(price);
        }

        closeDay(YESTERDAY, setup.manager())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSE_PRICES_MISSING"))
                .andExpect(jsonPath("$.params.fuelTypeCodes[0]").value("PETROL"));

        setFuelPrice(setup, "100.00");
        closeDay(YESTERDAY, setup.manager())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balanceSheetId").doesNotExist())
                .andExpect(jsonPath("$.summary.closedShiftCount").value(0))
                .andExpect(jsonPath("$.summary.sales.flag.reasons[0].code").value("SALES_NO_CLOSED_SHIFTS"));
    }

    @Test
    void safeCountedNextMorningForYesterdayLetsYesterdayClose() throws Exception {
        assumeTrue(TestDates.now().toLocalTime().isBefore(LocalTime.of(23, 59)), "needs a minute before the cut-off");
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(), Map.of("safeCountCutoffTime", "23:59"))
                .andExpect(status().isOk());
        perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(),
                Map.of("countedAmount", BigDecimal.ZERO, "countForDate", YESTERDAY.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.late").value(true));

        perform(get("/api/pumps/{pumpId}/day-closes/preview", setup.pumpId()).param("date", TODAY.toString()), setup.manager())
                .andExpect(jsonPath("$.checks[0].done").value(false));
        closeDay(YESTERDAY, setup.manager())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void warningsDoNotBlockAndTheStoredSummaryShowsEachSectionWithItsFlag() throws Exception {
        long shortShift = openShift();
        closeShift(shortShift, "900.00").andExpect(jsonPath("$.status").value("CLOSED_DISCREPANCY_PENDING"));
        countSafe();

        JsonNode closed = json(closeDay(TODAY, setup.manager()).andExpect(status().isOk()));

        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("version").asInt()).isEqualTo(1);
        assertThat(closed.get("balanceSheetId").isNull()).isFalse();
        JsonNode summary = closed.get("summary");
        assertThat(summary.get("closedShiftCount").asInt()).isEqualTo(1);
        assertThat(summary.get("openShiftCount").asInt()).isZero();
        assertThat(summary.get("warnings").findValuesAsText("code")).contains("TANKS_WITHOUT_DIP").doesNotContain("OPEN_SHIFTS");

        JsonNode sales = summary.get("sales");
        assertThat(sales.get("flag").get("reasons").findValuesAsText("code")).doesNotContain("SALES_OPEN_SHIFTS_EXCLUDED");
        assertThat(sales.get("fuelLitres").decimalValue()).isEqualByComparingTo("10.000");
        assertThat(sales.get("fuelRevenue").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(sales.get("fuelCost").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(sales.get("grossMargin").decimalValue()).isEqualByComparingTo("100.00");

        JsonNode cash = summary.get("cash");
        assertThat(cash.get("flag").get("colour").asText()).isEqualTo("AMBER");
        assertThat(cash.get("flag").get("reasons").findValuesAsText("code")).containsExactly("CASH_SHIFT_SHORTAGES");
        assertThat(cash.get("cash").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(cash.get("shortageAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(cash.get("safeCount").get("beyondTolerance").asBoolean()).isFalse();

        JsonNode stock = summary.get("stock");
        assertThat(stock.get("flag").get("reasons").findValuesAsText("code")).contains("STOCK_TANKS_NOT_DIPPED");
        assertThat(stock.get("tanks").get(0).get("dipped").asBoolean()).isFalse();
        assertThat(summary.get("credit").get("creditGiven").decimalValue()).isEqualByComparingTo("0.00");

        assertThat(notificationsOf(NotificationType.DAY_CLOSED)).singleElement()
                .satisfies(notification -> assertThat(notification.getParams()).containsEntry("date", TODAY.toString()));
        assertThat(auditsOf(AuditAction.DAY_CLOSED)).hasSize(1);
    }

    @Test
    void aShiftStillOpenBlocksTheCloseUntilItIsClosed() throws Exception {
        long closedShift = openShift();
        closeShift(closedShift, "1000.00").andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));
        long stillOpen = openShift();
        for (Shift shift : shiftRepository.findAllById(List.of(closedShift, stillOpen))) {
            shift.setShiftDate(YESTERDAY);
            shiftRepository.save(shift);
        }
        safeCountOn(YESTERDAY);

        perform(get("/api/pumps/{pumpId}/day-closes/preview", setup.pumpId()).param("date", YESTERDAY.toString()), setup.manager())
                .andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.checks[2].code").value("OPEN_SHIFTS"))
                .andExpect(jsonPath("$.checks[2].blocking").value(true))
                .andExpect(jsonPath("$.checks[2].done").value(false))
                .andExpect(jsonPath("$.checks[2].params.count").value(1));
        closeDay(YESTERDAY, setup.manager())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSE_OPEN_SHIFTS"))
                .andExpect(jsonPath("$.params.count").value(1));

        closeShift(stillOpen, "1000.00");
        JsonNode closed = json(closeDay(YESTERDAY, setup.manager()).andExpect(status().isOk()));

        assertThat(closed.get("summary").get("openShiftCount").asInt()).isZero();
        perform(get("/api/pumps/{pumpId}/balance-sheets/{id}", setup.pumpId(), closed.get("balanceSheetId").asLong()), setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportType").value("DAY"))
                .andExpect(jsonPath("$.shiftLines.length()").value(2));
    }

    @Test
    void pendingExpensesBlockAPastDayButOnlyWarnWhenClosingToday() throws Exception {
        long pastExpense = pendingExpense(YESTERDAY);
        safeCountOn(YESTERDAY);

        perform(get("/api/pumps/{pumpId}/day-closes/preview", setup.pumpId()).param("date", YESTERDAY.toString()), setup.manager())
                .andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.checks[3].code").value("PENDING_EXPENSES"))
                .andExpect(jsonPath("$.checks[3].blocking").value(true))
                .andExpect(jsonPath("$.checks[3].params.amount").value("5000.00"))
                .andExpect(jsonPath("$.checks[3].fixPath").value("/dashboard/approvals?kind=expenses"));
        closeDay(YESTERDAY, setup.manager())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSE_PENDING_APPROVALS"))
                .andExpect(jsonPath("$.params.missingCodes[0]").value("PENDING_EXPENSES"));
        perform(patch("/api/pumps/{pumpId}/expenses/{id}/approve", setup.pumpId(), pastExpense), setup.owner(),
                Map.of("action", "APPROVED")).andExpect(status().isOk());
        closeDay(YESTERDAY, setup.manager()).andExpect(status().isOk());

        long todayExpense = pendingExpense(TODAY);
        countSafe();
        JsonNode closedToday = json(closeDay(TODAY, setup.manager()).andExpect(status().isOk()));
        assertThat(closedToday.get("summary").get("warnings").findValuesAsText("code")).contains("PENDING_EXPENSES");
        perform(patch("/api/pumps/{pumpId}/expenses/{id}/approve", setup.pumpId(), todayExpense), setup.owner(),
                Map.of("action", "APPROVED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"));
    }

    @Test
    void ownerApprovesWhatTheManagerClosedAfterAskingAQuestion() throws Exception {
        countSafe();
        closeDay(TODAY, setup.manager()).andExpect(status().isOk());

        assertThat(attentionTypes()).contains("DAY_CLOSES_UNAPPROVED");
        perform(get("/api/pumps/{pumpId}/day-closes", setup.pumpId()).param("status", "CLOSED"), setup.owner())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].businessDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.content[0].closedByName").value(setup.manager().getFullName()))
                .andExpect(jsonPath("$.content[0].cashFlag").value("GREEN"));

        comment(setup.owner(), "Why was the tank not dipped?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[0].kind").value("QUESTION"));
        comment(setup.manager(), "The dipstick was being repaired.")
                .andExpect(jsonPath("$.comments[1].kind").value("REPLY"))
                .andExpect(jsonPath("$.comments[1].authorName").value(setup.manager().getFullName()));
        assertThat(notificationsOf(NotificationType.DAY_CLOSE_COMMENT)).hasSize(2);

        approve(setup.manager()).andExpect(status().isForbidden());
        approve(setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByName").value(setup.owner().getFullName()));
        approve(setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSE_NOT_AWAITING_APPROVAL"));

        assertThat(attentionTypes()).doesNotContain("DAY_CLOSES_UNAPPROVED");
        assertThat(auditsOf(AuditAction.DAY_APPROVED)).hasSize(1);
        assertThat(auditsOf(AuditAction.DAY_CLOSE_COMMENTED)).hasSize(2);
    }

    @Test
    void ownerOrAdminCanCloseAndApproveInOneStepButAManagerCannot() throws Exception {
        countSafe();

        closeDay(TODAY, setup.manager(), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSE_APPROVE_NOT_ALLOWED"));
        closeDay(TODAY, setup.admin(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByName").value(setup.admin().getFullName()));

        assertThat(notificationsOf(NotificationType.DAY_CLOSED)).isEmpty();
        assertThat(auditsOf(AuditAction.DAY_APPROVED)).hasSize(1);
    }

    @Test
    void reopeningNeedsAnOwnerOrAdminAndAReasonAndClosingAgainAddsAVersion() throws Exception {
        countSafe();
        closeDay(TODAY, setup.manager()).andExpect(status().isOk());

        reopen(setup.manager(), "Late expense").andExpect(status().isForbidden());
        reopen(setup.owner(), "  ").andExpect(status().isBadRequest());
        reopen(setup.owner(), "Late expense bill arrived")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REOPENED"))
                .andExpect(jsonPath("$.comments[0].kind").value("REOPEN_REASON"))
                .andExpect(jsonPath("$.comments[0].message").value("Late expense bill arrived"));
        reopen(setup.owner(), "Again")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_NOT_CLOSED"));
        assertThat(auditsOf(AuditAction.DAY_REOPENED)).singleElement()
                .satisfies(audit -> assertThat(audit.getDescription()).contains("Late expense bill arrived"));
        assertThat(notificationsOf(NotificationType.DAY_REOPENED)).hasSize(1);

        closeDay(TODAY, setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].version").value(2))
                .andExpect(jsonPath("$.versions[1].closedByName").value(setup.manager().getFullName()));
        perform(get("/api/pumps/{pumpId}/day-closes/{date}/versions/{version}", setup.pumpId(), TODAY, 1), setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value(TODAY.toString()));
        closeDay(TODAY, setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_ALREADY_CLOSED"));
    }

    @Test
    void futureDayCannotBeClosedAndADayNeverClosedIsNotFound() throws Exception {
        closeDay(TODAY.plusDays(1), setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSE_DATE_IN_FUTURE"));
        perform(get("/api/pumps/{pumpId}/day-closes/{date}", setup.pumpId(), YESTERDAY), setup.owner())
                .andExpect(status().isNotFound());
    }

    private ResultActions closeDay(LocalDate date, User user) throws Exception {
        return perform(post("/api/pumps/{pumpId}/day-closes/{date}/close", setup.pumpId(), date), user, Map.of());
    }

    private ResultActions closeDay(LocalDate date, User user, boolean approve) throws Exception {
        return perform(post("/api/pumps/{pumpId}/day-closes/{date}/close", setup.pumpId(), date), user,
                Map.of("approve", approve));
    }

    private ResultActions approve(User user) throws Exception {
        return perform(post("/api/pumps/{pumpId}/day-closes/{date}/approve", setup.pumpId(), TODAY), user);
    }

    private ResultActions comment(User user, String message) throws Exception {
        return perform(post("/api/pumps/{pumpId}/day-closes/{date}/comments", setup.pumpId(), TODAY), user,
                Map.of("message", message));
    }

    private ResultActions reopen(User user, String reason) throws Exception {
        return perform(post("/api/pumps/{pumpId}/day-closes/{date}/reopen", setup.pumpId(), TODAY), user,
                Map.of("reason", reason));
    }

    private void countSafe() throws Exception {
        BigDecimal balance = json(perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager()))
                .get("balance").decimalValue();
        perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(), Map.of("countedAmount", balance))
                .andExpect(status().isCreated());
    }

    private void safeCountOn(LocalDate day) {
        safeCountRepository.save(SafeCount.builder()
                .pumpId(setup.pumpId())
                .countedAmount(BigDecimal.ZERO)
                .expectedAmount(BigDecimal.ZERO)
                .varianceAmount(BigDecimal.ZERO)
                .toleranceAmount(BigDecimal.TEN)
                .countedByUserId(setup.manager().getId())
                .countedAt(day.atTime(20, 0).atZone(TestDates.BUSINESS_ZONE).toOffsetDateTime())
                .countForDate(day)
                .build());
    }

    private long openShift() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    /** Sells 10 L at ₹100; any cash short of ₹1000 is recorded as a shortage. */
    private ResultActions closeShift(long shiftId, String cash) throws Exception {
        BigDecimal endReading = nozzleRepository.findById(setup.nozzle().getId()).orElseThrow().getLastReading()
                .add(BigDecimal.TEN);
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", endReading)));
        request.put("cashCollected", new BigDecimal(cash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        request.put("discrepancyReason", "Counted short");
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), request)
                .andExpect(status().isOk());
    }

    private long pendingExpense(LocalDate date) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/expenses", setup.pumpId()), setup.manager(),
                Map.of("category", "MAINTENANCE", "amount", new BigDecimal("5000.00"), "description", "Pump motor rewinding",
                        "expenseDate", date.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalStatus").value("PENDING_APPROVAL"))).get("id").asLong();
    }

    private List<String> attentionTypes() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/attention", setup.pumpId()), setup.owner())
                .andExpect(status().isOk())).findValuesAsText("type");
    }

    private List<Notification> notificationsOf(NotificationType type) {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getPumpId().equals(setup.pumpId()) && notification.getType() == type)
                .toList();
    }

    private List<AuditLog> auditsOf(AuditAction action) {
        return auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()).stream()
                .filter(audit -> audit.getAction() == action)
                .toList();
    }
}
