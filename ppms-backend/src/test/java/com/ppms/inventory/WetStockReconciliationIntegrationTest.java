package com.ppms.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.fuel.FuelType;
import com.ppms.notification.Notification;
import com.ppms.notification.NotificationRepository;
import com.ppms.notification.NotificationType;
import com.ppms.scheduler.WetStockLossAlertJob;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftFuelReading;
import com.ppms.shift.ShiftFuelReadingRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seeds four days of one tank: an opening dip, a day reconciled exactly, then a delivery day without a dip merged
 * into the next day, whose closing dip is 20 L short of the 2,000 L sold (1%, above the 0.5% default limit).
 */
class WetStockReconciliationIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();
    private static final LocalDate OPENING_DAY = TODAY.minusDays(4);
    private static final LocalDate FULL_DAY = TODAY.minusDays(3);
    private static final LocalDate NO_DIP_DAY = TODAY.minusDays(2);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired private DipCheckRepository dipCheckRepository;
    @Autowired private TankerDeliveryRepository deliveryRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftFuelReadingRepository fuelReadingRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private WetStockLossAlertJob alertJob;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DipCheckNozzleReadingRepository dipSnapshotRepository;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("0");
    }

    @Test
    void reconcilesEachDipDayFromTheMeteredSalesOfClosedShiftsAndTheDeliveries() throws Exception {
        seedFourDays("9980");

        JsonNode tank = reconciliation(FULL_DAY, YESTERDAY).get("tanks").get(0);
        JsonNode periods = tank.get("periods");

        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).get("periodStart").asText()).isEqualTo(FULL_DAY.toString());
        assertThat(periods.get(0).get("openingLitres").decimalValue()).isEqualByComparingTo("10000");
        assertThat(periods.get(0).get("soldLitres").decimalValue()).isEqualByComparingTo("1000");
        assertThat(periods.get(0).get("varianceLitres").decimalValue()).isEqualByComparingTo("0");
        JsonNode merged = periods.get(1);
        assertThat(merged.get("periodStart").asText()).isEqualTo(NO_DIP_DAY.toString());
        assertThat(merged.get("periodEnd").asText()).isEqualTo(YESTERDAY.toString());
        assertThat(merged.get("days").asInt()).isEqualTo(2);
        assertThat(merged.get("deliveredLitres").decimalValue()).isEqualByComparingTo("2000");
        assertThat(merged.get("soldLitres").decimalValue()).isEqualByComparingTo("1000");
        assertThat(merged.get("expectedClosingLitres").decimalValue()).isEqualByComparingTo("10000");
        assertThat(merged.get("varianceLitres").decimalValue()).isEqualByComparingTo("-20");
        assertThat(merged.get("variancePercent").decimalValue()).isEqualByComparingTo("-2.00");
        assertThat(merged.get("withinTolerance").asBoolean()).isTrue();
        assertThat(tank.get("summary").get("variancePercent").decimalValue()).isEqualByComparingTo("-1.00");
        assertThat(tank.get("summary").get("status").asText()).isEqualTo("LOSS_ABOVE_LIMIT");
        assertThat(tank.get("lossAlertPercent").decimalValue()).isEqualByComparingTo("0.5");
    }

    @Test
    void firstDipEverNeedsAnOpeningDip() throws Exception {
        seedFourDays("9980");

        JsonNode first = reconciliation(OPENING_DAY, YESTERDAY).get("tanks").get(0).get("periods").get(0);

        assertThat(first.get("status").asText()).isEqualTo("NEEDS_OPENING_DIP");
        assertThat(first.get("closingLitres").decimalValue()).isEqualByComparingTo("10000");
        assertThat(first.get("varianceLitres").isNull()).isTrue();
    }

    @Test
    void lossAboveTheLimitRaisesOneNotificationPerTankAndDayAndAnAttentionItem() throws Exception {
        seedFourDays("9980");

        alertJob.checkAllPumps();
        alertJob.checkAllPumps();

        List<Notification> alerts = wetStockAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getDedupKey()).isEqualTo("WET_STOCK_LOSS:tank:" + setup.tank().getId() + ":" + TODAY);
        assertThat(alerts.get(0).getParams()).containsEntry("lossPercent", "1.00").containsEntry("limitPercent", "0.5");
        JsonNode attention = json(perform(get("/api/pumps/{pumpId}/attention", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        assertThat(attention.findValuesAsText("type")).contains("WET_STOCK_LOSS");
    }

    @Test
    void lossWithinTheLimitRaisesNothing() throws Exception {
        seedFourDays("9995");

        alertJob.checkAllPumps();

        assertThat(wetStockAlerts()).isEmpty();
    }

    @Test
    void ownerCanRaiseTheTanksLossLimitSoTheSameLossNoLongerAlerts() throws Exception {
        seedFourDays("9980");

        perform(patch("/api/pumps/{pumpId}/tanks/{tankId}", setup.pumpId(), setup.tank().getId()), setup.owner(),
                Map.of("capacity", 20000, "lossAlertPercent", 1.5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lossAlertPercent").value(1.5));
        alertJob.checkAllPumps();

        assertThat(wetStockAlerts()).isEmpty();
    }

    @Test
    void managerCannotChangeTheLossLimitAndOutOfRangeLimitsAreRefused() throws Exception {
        String path = "/api/pumps/{pumpId}/tanks/{tankId}";
        perform(patch(path, setup.pumpId(), setup.tank().getId()), setup.manager(),
                Map.of("capacity", 20000, "lossAlertPercent", 1)).andExpect(status().isForbidden());
        perform(patch(path, setup.pumpId(), setup.tank().getId()), setup.owner(),
                Map.of("capacity", 20000, "lossAlertPercent", 0)).andExpect(status().isBadRequest());
        perform(patch(path, setup.pumpId(), setup.tank().getId()), setup.owner(),
                Map.of("capacity", 20000, "lossAlertPercent", 101)).andExpect(status().isBadRequest());
    }

    @Test
    void activeTankWithFuelAndNoDipYesterdayIsNudged() throws Exception {
        jdbcTemplate.update("UPDATE underground_tanks SET current_stock = 500, created_at = ? WHERE id = ?",
                TestDates.now().minusDays(10), setup.tank().getId());

        JsonNode attention = json(perform(get("/api/pumps/{pumpId}/attention", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));

        assertThat(attention.findValuesAsText("type")).contains("MISSING_DIPS");
    }

    @Test
    void invalidRangesAndParametersAreRefused() throws Exception {
        String path = "/api/inventory/{pumpId}/reconciliation";
        perform(get(path, setup.pumpId()).param("from", TODAY.toString()).param("to", YESTERDAY.toString()), setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATE_RANGE_START_AFTER_END"));
        perform(get(path, setup.pumpId()).param("from", TODAY.minusDays(92).toString()).param("to", TODAY.toString()), setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATE_RANGE_TOO_LONG"));
        perform(get(path, setup.pumpId()).param("from", TODAY.toString()).param("to", TODAY.plusDays(1).toString()), setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATE_RANGE_END_IN_FUTURE"));
        perform(get(path, setup.pumpId()).param("from", TODAY.toString()), setup.owner())
                .andExpect(status().isBadRequest());
        perform(get(path, setup.pumpId()).param("from", "yesterday").param("to", TODAY.toString()), setup.owner())
                .andExpect(status().isBadRequest());
        perform(get(path, setup.pumpId()).param("from", TODAY.minusDays(91).toString()).param("to", TODAY.toString()), setup.owner())
                .andExpect(status().isOk());
    }

    @Test
    void tankOfAnotherPumpIsNotFoundAndOperatorsCannotRead() throws Exception {
        PumpSetup otherPump = createPumpWithStock("0");
        String path = "/api/inventory/{pumpId}/reconciliation";

        perform(get(path, setup.pumpId()).param("from", YESTERDAY.toString()).param("to", TODAY.toString())
                .param("tankId", otherPump.tank().getId().toString()), setup.owner())
                .andExpect(status().isNotFound());
        perform(get(path, setup.pumpId()).param("from", YESTERDAY.toString()).param("to", TODAY.toString()), setup.operator())
                .andExpect(status().isForbidden());
    }

    @Test
    void queryCountDoesNotGrowWithTheNumberOfDays() throws Exception {
        seedFourDays("9980");
        String path = "/api/inventory/{pumpId}/reconciliation";

        long shortRange = statementsRunBy(get(path, setup.pumpId())
                .param("from", YESTERDAY.toString()).param("to", TODAY.toString()), setup.manager());
        long longRange = statementsRunBy(get(path, setup.pumpId())
                .param("from", TODAY.minusDays(60).toString()).param("to", TODAY.toString()), setup.manager());

        assertThat(longRange).isEqualTo(shortRange);
    }

    @Test
    void dipTakenMidShiftCountsOnlyWhatItsMeterSnapshotShowsSoNeitherDayShowsAFalseGainOrLoss() throws Exception {
        saveDip(OPENING_DAY, 21, "10000");
        Shift shift = saveShift(FULL_DAY, atHour(FULL_DAY, 8), atHour(NO_DIP_DAY, 2), "0", "1000", "1000");
        DipCheck midShiftDip = saveDipAt(atHour(FULL_DAY, 12), "9600");
        saveSnapshot(midShiftDip, shift, "0", "400", "400");
        saveDip(NO_DIP_DAY, 21, "9000");

        JsonNode periods = reconciliation(FULL_DAY, NO_DIP_DAY).get("tanks").get(0).get("periods");

        assertThat(periods).extracting(period -> period.get("soldLitres").decimalValue())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("400"), new BigDecimal("600"));
        assertThat(periods).extracting(period -> period.get("varianceLitres").decimalValue())
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void meterThatRolledOverBetweenTwoDipsCountsOnlyTheLitresDispensed() throws Exception {
        saveDip(OPENING_DAY, 21, "10000");
        Shift shift = saveShift(FULL_DAY, atHour(FULL_DAY, 8), atHour(FULL_DAY, 20), "99999990.000", "10.000", "20.000");
        DipCheck midShiftDip = saveDipAt(atHour(FULL_DAY, 12), "9995");
        saveSnapshot(midShiftDip, shift, "99999990.000", "99999995.000", "5.000");
        saveDip(FULL_DAY, 21, "9980");

        JsonNode tank = reconciliation(FULL_DAY, FULL_DAY).get("tanks").get(0);

        assertThat(tank.get("summary").get("salesLitres").decimalValue()).isEqualByComparingTo("20");
        assertThat(tank.get("periods").get(0).get("varianceLitres").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void litresSoldWhileADeliveryWasUnloadedAreInBothTheReceivedLitresAndTheMetersSoTheyCancelOut() throws Exception {
        saveDip(OPENING_DAY, 21, "2000");
        TankerDelivery delivery = deliveryRepository.save(TankerDelivery.builder()
                .pumpId(setup.pumpId())
                .tankId(setup.tank().getId())
                .fuelType(FuelType.PETROL)
                .quantityDelivered(new BigDecimal("8000"))
                .costPricePerUnit(new BigDecimal("90"))
                .deliveryDate(atHour(FULL_DAY, 11))
                .invoiceReference("INV-" + shortId())
                .loggedByUserId(setup.manager().getId())
                .dipBeforeLitres(new BigDecimal("1500"))
                .dipAfterLitres(new BigDecimal("9500"))
                .soldBetweenLitres(new BigDecimal("100"))
                .receivedLitres(new BigDecimal("8100"))
                .varianceLitres(new BigDecimal("100"))
                .receiptTolerancePercent(new BigDecimal("0.20"))
                .densityTolerance(new BigDecimal("3.0"))
                .checkStatus(DeliveryCheckStatus.OVER)
                .build());
        saveShift(FULL_DAY, atHour(FULL_DAY, 8), atHour(FULL_DAY, 20), "0", "1000", "1000");
        saveDip(FULL_DAY, 21, "9100");

        JsonNode period = reconciliation(FULL_DAY, FULL_DAY).get("tanks").get(0).get("periods").get(0);

        assertThat(period.get("deliveredLitres").decimalValue()).isEqualByComparingTo(delivery.getReceivedLitres());
        assertThat(period.get("soldLitres").decimalValue()).isEqualByComparingTo("1000");
        assertThat(period.get("varianceLitres").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void amendedClosingReadingIsWhatTheMetersSoldBetweenTheDips() throws Exception {
        PumpSetup stocked = createPumpWithStock("5000");
        setup = stocked;
        saveDip(YESTERDAY, 23, "5000");
        long shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId())).andExpect(status().isCreated())).get("id").asLong();
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(),
                closeRequest("1010.000", "1000.00")).andExpect(status().isOk());
        Map<String, Object> amendment = closeRequest("1020.000", "2000.00");
        amendment.put("reason", "Closing reading was misread");
        perform(post("/api/pumps/{pumpId}/shifts/{id}/amend", setup.pumpId(), shiftId), setup.owner(), amendment)
                .andExpect(status().isOk());
        saveDipAt(TestDates.now().plusSeconds(1), "4980");

        JsonNode period = reconciliation(TODAY, TODAY).get("tanks").get(0).get("periods").get(0);

        assertThat(period.get("soldLitres").decimalValue()).isEqualByComparingTo("20");
        assertThat(period.get("varianceLitres").decimalValue()).isEqualByComparingTo("0");
    }

    private Map<String, Object> closeRequest(String endReading, String cash) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal(endReading))));
        request.put("cashCollected", new BigDecimal(cash));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", BigDecimal.ZERO);
        request.put("creditEntries", List.of());
        return request;
    }

    private DipCheck saveDipAt(OffsetDateTime checkedAt, String litres) {
        return dipCheckRepository.save(DipCheck.builder()
                .pumpId(setup.pumpId())
                .tankId(setup.tank().getId())
                .measuredQuantity(new BigDecimal(litres))
                .systemStock(new BigDecimal(litres))
                .checkedAt(checkedAt)
                .loggedByUserId(setup.manager().getId())
                .status(DipCheckStatus.WITHIN_TOLERANCE)
                .build());
    }

    private void saveSnapshot(DipCheck dip, Shift shift, String opening, String meter, String unitsSold) {
        dipSnapshotRepository.save(DipCheckNozzleReading.builder()
                .dipCheckId(dip.getId())
                .shiftId(shift.getId())
                .nozzleId(setup.nozzle().getId())
                .openingReading(new BigDecimal(opening))
                .meterReading(new BigDecimal(meter))
                .unitsSold(new BigDecimal(unitsSold))
                .build());
    }

    private Shift saveShift(LocalDate day, OffsetDateTime openedAt, OffsetDateTime closedAt, String start, String end,
                            String unitsSold) {
        Shift shift = shiftRepository.save(Shift.builder()
                .pumpId(setup.pumpId())
                .duId(setup.du().getId())
                .operatorId(setup.operator().getId())
                .openedByUserId(setup.manager().getId())
                .shiftDate(day)
                .actualStartTime(openedAt)
                .actualEndTime(closedAt)
                .closedAt(closedAt)
                .status(ShiftStatus.CLOSED_BALANCED)
                .build());
        fuelReadingRepository.save(ShiftFuelReading.builder()
                .shiftId(shift.getId())
                .nozzleId(setup.nozzle().getId())
                .tankId(setup.tank().getId())
                .fuelType(FuelType.PETROL)
                .startReading(new BigDecimal(start))
                .endReading(new BigDecimal(end))
                .unitsSold(new BigDecimal(unitsSold))
                .priceSnapshot(new BigDecimal("100"))
                .build());
        return shift;
    }

    private void seedFourDays(String yesterdaysClosingDip) {
        saveDip(OPENING_DAY, 20, "10000");
        saveSale(FULL_DAY, ShiftStatus.CLOSED_BALANCED, "1000");
        saveDip(FULL_DAY, 9, "9500");
        saveDip(FULL_DAY, 21, "9000");
        saveDelivery(NO_DIP_DAY, "2000");
        saveSale(NO_DIP_DAY, ShiftStatus.CLOSED_DISCREPANCY_RESOLVED, "500");
        saveSale(NO_DIP_DAY, ShiftStatus.AUTO_CLOSED_OVERDUE, "300");
        saveSale(YESTERDAY, ShiftStatus.CLOSED_BALANCED, "500");
        saveDip(YESTERDAY, 21, yesterdaysClosingDip);
    }

    private void saveDip(LocalDate day, int hour, String litres) {
        dipCheckRepository.save(DipCheck.builder()
                .pumpId(setup.pumpId())
                .tankId(setup.tank().getId())
                .measuredQuantity(new BigDecimal(litres))
                .systemStock(new BigDecimal(litres))
                .checkedAt(atHour(day, hour))
                .loggedByUserId(setup.manager().getId())
                .status(DipCheckStatus.WITHIN_TOLERANCE)
                .build());
    }

    private void saveSale(LocalDate day, ShiftStatus status, String litres) {
        OffsetDateTime start = atHour(day, 8);
        Shift shift = shiftRepository.save(Shift.builder()
                .pumpId(setup.pumpId())
                .duId(setup.du().getId())
                .operatorId(setup.operator().getId())
                .openedByUserId(setup.manager().getId())
                .shiftDate(day)
                .actualStartTime(start)
                .actualEndTime(start.plusHours(8))
                .status(status)
                .build());
        fuelReadingRepository.save(ShiftFuelReading.builder()
                .shiftId(shift.getId())
                .nozzleId(setup.nozzle().getId())
                .tankId(setup.tank().getId())
                .fuelType(FuelType.PETROL)
                .startReading(BigDecimal.ZERO)
                .endReading(new BigDecimal(litres))
                .unitsSold(new BigDecimal(litres))
                .priceSnapshot(new BigDecimal("100"))
                .build());
    }

    private void saveDelivery(LocalDate day, String litres) {
        deliveryRepository.save(TankerDelivery.builder()
                .pumpId(setup.pumpId())
                .tankId(setup.tank().getId())
                .fuelType(FuelType.PETROL)
                .quantityDelivered(new BigDecimal(litres))
                .costPricePerUnit(new BigDecimal("90"))
                .deliveryDate(atHour(day, 11))
                .invoiceReference("INV-" + shortId())
                .loggedByUserId(setup.manager().getId())
                .build());
    }

    private JsonNode reconciliation(LocalDate from, LocalDate to) throws Exception {
        return json(perform(get("/api/inventory/{pumpId}/reconciliation", setup.pumpId())
                .param("from", from.toString()).param("to", to.toString()), setup.manager())
                .andExpect(status().isOk()));
    }

    private List<Notification> wetStockAlerts() {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getPumpId().equals(setup.pumpId()))
                .filter(notification -> notification.getType() == NotificationType.WET_STOCK_LOSS)
                .toList();
    }

    private static OffsetDateTime atHour(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(TestDates.BUSINESS_ZONE).toOffsetDateTime();
    }
}
