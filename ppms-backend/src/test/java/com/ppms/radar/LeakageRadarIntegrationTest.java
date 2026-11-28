package com.ppms.radar;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.adjustment.NozzleReadingAdjustment;
import com.ppms.adjustment.NozzleReadingAdjustmentRepository;
import com.ppms.cash.CashDrop;
import com.ppms.cash.CashDropRepository;
import com.ppms.cash.CashDropStatus;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditEntryReassignment;
import com.ppms.credit.CreditEntryReassignmentRepository;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.DipCheck;
import com.ppms.inventory.DipCheckRepository;
import com.ppms.inventory.DipCheckStatus;
import com.ppms.notification.Notification;
import com.ppms.notification.NotificationRepository;
import com.ppms.notification.NotificationType;
import com.ppms.pump.Nozzle;
import com.ppms.scheduler.LeakageRadarWeeklyJob;
import com.ppms.settlement.PaymentSettlement;
import com.ppms.settlement.PaymentSettlementRepository;
import com.ppms.settlement.SettlementPaymentType;
import com.ppms.shift.DiscrepancyType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.shift.ShiftFuelReading;
import com.ppms.shift.ShiftFuelReadingRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeakageRadarIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();
    private static final String RADAR_PATH = "/api/pumps/{pumpId}/leakage-radar";

    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftFuelReadingRepository fuelReadingRepository;
    @Autowired private ShiftCreditEntryRepository creditEntryRepository;
    @Autowired private CreditClientRepository creditClientRepository;
    @Autowired private CreditEntryReassignmentRepository reassignmentRepository;
    @Autowired private PaymentSettlementRepository settlementRepository;
    @Autowired private CashDropRepository cashDropRepository;
    @Autowired private NozzleReadingAdjustmentRepository adjustmentRepository;
    @Autowired private DipCheckRepository dipCheckRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private LeakageRadarWeeklyJob weeklyJob;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("0");
    }

    @Test
    void operatorShortFarMoreOftenThanTheRestOfThePumpIsWorthALookAndANewcomerIsNotEnoughData() throws Exception {
        User steady = saveUser(UserRole.OPERATOR, setup.pumpId());
        User newcomer = saveUser(UserRole.OPERATOR, setup.pumpId());
        for (int day = 0; day < 10; day++) {
            boolean isShort = day < 6;
            saveShift(setup.operator(), TODAY.minusDays(day), isShort ? DiscrepancyType.SHORT : null, isShort ? "300" : null);
            saveShift(steady, TODAY.minusDays(day), null, null);
        }
        saveShift(newcomer, TODAY.minusDays(1), DiscrepancyType.SHORT, "900");

        JsonNode radar = radar(30, setup.owner());

        JsonNode shortOperator = person(radar, setup.operator());
        assertThat(shortOperator.get("shiftCount").asInt()).isEqualTo(10);
        assertThat(shortOperator.get("shortShiftCount").asInt()).isEqualTo(6);
        assertThat(shortOperator.get("shortAmount").decimalValue()).isEqualByComparingTo("1800");
        assertThat(shortOperator.get("trend").asText()).isEqualTo("RISING");
        // 60% of shifts vs 1 in 11 for the rest -> 30; ₹180 vs ₹81.82 a shift -> 4; rising -> 10
        assertThat(shortOperator.get("score").asInt()).isEqualTo(44);
        assertThat(shortOperator.get("band").asText()).isEqualTo("WATCH");
        assertThat(shortOperator.get("evidence")).hasSize(6);
        assertThat(shortOperator.get("evidence").get(0).get("kind").asText()).isEqualTo("SHORT_SHIFT");
        assertThat(shortOperator.get("evidence").get(0).get("shiftId").isNumber()).isTrue();
        assertThat(person(radar, steady).get("band").asText()).isEqualTo("LOW");
        assertThat(person(radar, newcomer).get("band").asText()).isEqualTo("NOT_ENOUGH_DATA");
        assertThat(radar.get("people").get(0).get("userId").asLong()).isEqualTo(setup.operator().getId());
        assertThat(radar.get("people").findValuesAsText("role")).doesNotContain("OWNER");
        assertThat(radar.get("minimumShifts").asInt()).isEqualTo(5);
    }

    @Test
    void creditVoidsReassignmentsSettlementVoidsAndRejectedDropsCountAgainstThePersonWhoMadeThem() throws Exception {
        Shift shift = saveShift(setup.operator(), TODAY.minusDays(2), null, null);
        CreditClient from = creditClientRepository.save(CreditClient.builder().pumpId(setup.pumpId()).name("Fleet " + shortId()).build());
        CreditClient to = creditClientRepository.save(CreditClient.builder().pumpId(setup.pumpId()).name("Fleet " + shortId()).build());
        for (int i = 0; i < 3; i++) {
            saveVoidedCreditEntry(shift, setup.manager(), TestDates.now().minusDays(i + 1L));
        }
        saveVoidedCreditEntry(shift, setup.manager(), TestDates.now().minusDays(40));
        ShiftCreditEntry moved = saveCreditEntry(shift, from);
        for (int i = 0; i < 2; i++) {
            reassignmentRepository.save(CreditEntryReassignment.builder().creditEntryId(moved.getId())
                    .fromClientId(from.getId()).toClientId(to.getId()).reassignedByUserId(setup.manager().getId())
                    .reason("Billed to the wrong fleet").reassignedAt(TestDates.now().minusDays(1)).build());
        }
        settlementRepository.save(PaymentSettlement.builder().pumpId(setup.pumpId()).paymentType(SettlementPaymentType.UPI)
                .settlementDate(TODAY.minusDays(3)).amountReceived(new BigDecimal("4000")).recordedByUserId(setup.admin().getId())
                .voidStatus(PaymentSettlement.VOIDED).voidReason("Duplicate").voidedByUserId(setup.admin().getId())
                .voidedAt(TestDates.now().minusDays(3)).build());
        saveRejectedCashDrop(shift);

        JsonNode thirtyDays = radar(30, setup.owner());

        JsonNode manager = person(thirtyDays, setup.manager());
        assertThat(manager.get("correctionCount").asInt()).isEqualTo(5);
        // 5 corrections, 1 expected in 30 days -> 4 x 8
        assertThat(manager.get("score").asInt()).isEqualTo(32);
        assertThat(manager.get("band").asText()).isEqualTo("WATCH");
        assertThat(manager.get("evidence").findValuesAsText("kind"))
                .containsOnly("CREDIT_VOID", "CREDIT_REASSIGNED").hasSize(5);
        JsonNode admin = person(thirtyDays, setup.admin());
        assertThat(admin.get("correctionCount").asInt()).isEqualTo(1);
        assertThat(admin.get("evidence").get(0).get("kind").asText()).isEqualTo("SETTLEMENT_VOID");
        assertThat(admin.get("band").asText()).isEqualTo("LOW");
        JsonNode operator = person(thirtyDays, setup.operator());
        assertThat(operator.get("evidence").findValuesAsText("kind")).containsExactly("CASH_DROP_REJECTED");

        assertThat(person(radar(90, setup.owner()), setup.manager()).get("correctionCount").asInt()).isEqualTo(6);
    }

    @Test
    void readingThatGoesBackwardsBetweenShiftsAndAnAmendmentScoreTheNozzleButARolloverDoesNot() throws Exception {
        BigDecimal reading = new BigDecimal("1000");
        for (int day = 6; day >= 2; day--) {
            saveSale(setup.nozzle(), TODAY.minusDays(day), reading, reading.add(new BigDecimal("500")));
            reading = reading.add(new BigDecimal("500"));
        }
        saveSale(setup.nozzle(), TODAY.minusDays(1), new BigDecimal("3000"), new BigDecimal("3500"));
        adjustmentRepository.save(NozzleReadingAdjustment.builder().pumpId(setup.pumpId()).nozzleId(setup.nozzle().getId())
                .adjustmentType("CUSTOM_READING").fuelType("PETROL").previousReading(new BigDecimal("3500"))
                .newReading(new BigDecimal("3400")).reason("Typed wrong").recordedByUserId(setup.manager().getId()).build());

        Nozzle rolling = nozzleRepository.save(Nozzle.builder().duId(setup.du().getId()).nozzleNumber(2)
                .fuelType(FuelType.PETROL).lastReading(new BigDecimal("1010")).maxMeterValue(new BigDecimal("9999.999"))
                .build());
        String[][] rollingReadings = {{"8500", "9000"}, {"9000", "9500"}, {"9500", "10"}, {"10", "510"}, {"510", "1010"}};
        for (int i = 0; i < rollingReadings.length; i++) {
            saveSale(rolling, TODAY.minusDays(6L - i), new BigDecimal(rollingReadings[i][0]), new BigDecimal(rollingReadings[i][1]));
        }

        JsonNode nozzles = radar(30, setup.owner()).get("nozzles");

        JsonNode jumped = nozzle(nozzles, setup.nozzle());
        assertThat(jumped.get("readingJumpCount").asInt()).isEqualTo(1);
        assertThat(jumped.get("amendmentCount").asInt()).isEqualTo(1);
        // backwards jump 20 + lowering amendment 25
        assertThat(jumped.get("score").asInt()).isEqualTo(45);
        assertThat(jumped.get("band").asText()).isEqualTo("WATCH");
        assertThat(jumped.get("evidence").findValuesAsText("kind")).contains("READING_JUMP", "METER_AMENDMENT");
        JsonNode rolled = nozzle(nozzles, rolling);
        assertThat(rolled.get("readingJumpCount").asInt()).isZero();
        assertThat(rolled.get("score").asInt()).isZero();
        assertThat(rolled.get("band").asText()).isEqualTo("LOW");
    }

    @Test
    void tankLosingAboveItsLimitEveryWeekScoresHigh() throws Exception {
        BigDecimal dip = new BigDecimal("10000");
        saveDip(TODAY.minusDays(28), dip);
        for (int weeksBack = 3; weeksBack >= 0; weeksBack--) {
            LocalDate dipDay = TODAY.minusDays(7L * weeksBack);
            saveSale(setup.nozzle(), dipDay.minusDays(3), new BigDecimal("1000"), new BigDecimal("2000"));
            dip = dip.subtract(new BigDecimal("1020"));
            saveDip(dipDay, dip);
        }

        JsonNode tank = radar(30, setup.owner()).get("tanks").get(0);

        assertThat(tank.get("tankId").asLong()).isEqualTo(setup.tank().getId());
        assertThat(tank.get("weeks")).hasSize(4);
        assertThat(tank.get("weeks").findValuesAsText("lossAboveLimit")).containsOnly("true");
        assertThat(tank.get("variancePercent").decimalValue()).isEqualByComparingTo("-2.00");
        assertThat(tank.get("score").asInt()).isEqualTo(100);
        assertThat(tank.get("band").asText()).isEqualTo("HIGH");
        assertThat(tank.get("evidence").get(0).get("kind").asText()).isEqualTo("LOSS_WEEK");
    }

    @Test
    void weeklySummaryIsSentOnceAWeekToOwnersAndAdminsOnly() throws Exception {
        for (int day = 0; day < 6; day++) {
            saveShift(setup.operator(), TODAY.minusDays(day), DiscrepancyType.SHORT, "2000");
        }
        for (int day = 0; day < 6; day++) {
            saveShift(saveUser(UserRole.OPERATOR, setup.pumpId()), TODAY.minusDays(day), null, null);
        }

        weeklyJob.sendWeeklySummaries();
        weeklyJob.sendWeeklySummaries();

        List<Notification> summaries = notificationRepository.findAll().stream()
                .filter(notification -> notification.getPumpId().equals(setup.pumpId()))
                .filter(notification -> notification.getType() == NotificationType.LEAKAGE_RADAR_WEEKLY)
                .toList();
        assertThat(summaries).hasSize(1);
        LocalDate monday = TODAY.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(summaries.get(0).getDedupKey()).isEqualTo("LEAKAGE_RADAR_WEEKLY:" + monday);
        assertThat(summaries.get(0).getParams()).containsEntry("people", 1).containsEntry("days", 30);
        assertThat(notificationTypes(setup.owner())).contains("LEAKAGE_RADAR_WEEKLY");
        assertThat(notificationTypes(setup.admin())).contains("LEAKAGE_RADAR_WEEKLY");
        assertThat(notificationTypes(setup.manager())).doesNotContain("LEAKAGE_RADAR_WEEKLY");
    }

    @Test
    void queryCountDoesNotGrowWithThePeriodOrTheNumberOfEvents() throws Exception {
        saveShift(setup.operator(), TODAY.minusDays(1), DiscrepancyType.SHORT, "500");
        long thirtyDays = statementsRunBy(get(RADAR_PATH, setup.pumpId()).param("days", "30"), setup.owner());
        long ninetyDays = statementsRunBy(get(RADAR_PATH, setup.pumpId()).param("days", "90"), setup.owner());

        Shift shift = saveShift(setup.operator(), TODAY.minusDays(2), null, null);
        for (int day = 3; day < 20; day++) {
            saveShift(saveUser(UserRole.OPERATOR, setup.pumpId()), TODAY.minusDays(day), DiscrepancyType.SHORT, "500");
            saveVoidedCreditEntry(shift, setup.manager(), TestDates.now().minusDays(day));
            saveSale(setup.nozzle(), TODAY.minusDays(day), new BigDecimal("1000"), new BigDecimal("1100"));
        }
        long moreEvents = statementsRunBy(get(RADAR_PATH, setup.pumpId()).param("days", "30"), setup.owner());

        assertThat(ninetyDays).isEqualTo(thirtyDays);
        assertThat(moreEvents).isEqualTo(thirtyDays);
    }

    private JsonNode radar(int days, User user) throws Exception {
        return json(perform(get(RADAR_PATH, setup.pumpId()).param("days", String.valueOf(days)), user)
                .andExpect(status().isOk()));
    }

    private static JsonNode person(JsonNode radar, User user) {
        return StreamSupport.stream(radar.get("people").spliterator(), false)
                .filter(person -> person.get("userId").asLong() == user.getId())
                .findFirst().orElseThrow(() -> new AssertionError("No score for user " + user.getId()));
    }

    private static JsonNode nozzle(JsonNode nozzles, Nozzle nozzle) {
        return StreamSupport.stream(nozzles.spliterator(), false)
                .filter(score -> score.get("nozzleId").asLong() == nozzle.getId())
                .findFirst().orElseThrow(() -> new AssertionError("No score for nozzle " + nozzle.getId()));
    }

    private List<String> notificationTypes(User user) throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/notifications", setup.pumpId()), user).andExpect(status().isOk()))
                .findValuesAsText("type");
    }

    private Shift saveShift(User operator, LocalDate day, DiscrepancyType type, String discrepancy) {
        OffsetDateTime start = atHour(day, 6);
        return shiftRepository.save(Shift.builder()
                .pumpId(setup.pumpId())
                .duId(setup.du().getId())
                .operatorId(operator.getId())
                .openedByUserId(setup.manager().getId())
                .shiftDefinitionId(setup.allDayShift().getId())
                .shiftDate(day)
                .actualStartTime(start)
                .actualEndTime(start.plusHours(8))
                .discrepancyType(type)
                .discrepancyAmount(discrepancy == null ? BigDecimal.ZERO : new BigDecimal(discrepancy))
                .status(type == null ? ShiftStatus.CLOSED_BALANCED : ShiftStatus.CLOSED_DISCREPANCY_PENDING)
                .build());
    }

    private void saveSale(Nozzle nozzle, LocalDate day, BigDecimal start, BigDecimal end) {
        Shift shift = saveShift(setup.operator(), day, null, null);
        BigDecimal litres = end.compareTo(start) >= 0 ? end.subtract(start) : nozzle.getMaxMeterValue().subtract(start).add(end);
        fuelReadingRepository.save(ShiftFuelReading.builder()
                .shiftId(shift.getId())
                .nozzleId(nozzle.getId())
                .tankId(setup.tank().getId())
                .fuelType(FuelType.PETROL)
                .startReading(start)
                .endReading(end)
                .unitsSold(litres)
                .priceSnapshot(new BigDecimal("100"))
                .build());
    }

    private ShiftCreditEntry saveCreditEntry(Shift shift, CreditClient client) {
        return creditEntryRepository.save(ShiftCreditEntry.builder().shiftId(shift.getId())
                .clientId(client == null ? null : client.getId()).clientName(client == null ? "Walk-in" : client.getName())
                .amount(new BigDecimal("750")).saleDate(shift.getShiftDate()).fuelType("PETROL").build());
    }

    private void saveVoidedCreditEntry(Shift shift, User voidedBy, OffsetDateTime voidedAt) {
        ShiftCreditEntry entry = saveCreditEntry(shift, null);
        entry.setVoidStatus("VOIDED");
        entry.setVoidReason("Entered twice");
        entry.setVoidedByUserId(voidedBy.getId());
        entry.setVoidedAt(voidedAt);
        creditEntryRepository.save(entry);
    }

    private void saveRejectedCashDrop(Shift shift) {
        cashDropRepository.save(CashDrop.builder().pumpId(setup.pumpId()).shiftId(shift.getId())
                .operatorId(setup.operator().getId()).amount(new BigDecimal("2000")).status(CashDropStatus.REJECTED)
                .recordedByUserId(setup.operator().getId()).reviewedByUserId(setup.manager().getId())
                .reviewedAt(TestDates.now().minusDays(2)).rejectionReason("Bag was short").build());
    }

    private void saveDip(LocalDate day, BigDecimal litres) {
        dipCheckRepository.save(DipCheck.builder()
                .pumpId(setup.pumpId())
                .tankId(setup.tank().getId())
                .measuredQuantity(litres)
                .systemStock(litres)
                .checkedAt(atHour(day, 5))
                .loggedByUserId(setup.manager().getId())
                .status(DipCheckStatus.WITHIN_TOLERANCE)
                .build());
    }

    private static OffsetDateTime atHour(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(TestDates.BUSINESS_ZONE).toOffsetDateTime();
    }
}
