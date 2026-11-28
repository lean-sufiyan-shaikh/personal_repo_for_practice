package com.ppms.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditLog;
import com.ppms.audit.AuditLogRepository;
import com.ppms.fuel.FuelType;
import com.ppms.notification.Notification;
import com.ppms.notification.NotificationRepository;
import com.ppms.notification.NotificationType;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pump's petrol tank starts empty with one nozzle reading 1,000; deliveries are invoiced at ₹90/L and the
 * default tolerances are 0.20% of the invoice litres and 3.0 kg/m³.
 */
class DeliveryReceiptCheckIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private TankerDeliveryRepository deliveryRepository;
    @Autowired private InventoryLotRepository lotRepository;
    @Autowired private ShortageClaimRepository claimRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private DipCheckRepository dipCheckRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("0");
    }

    @Test
    void litresSoldWhileUnloadingCountAsReceivedAndAShortfallOfExactlyTheToleranceIsOk() throws Exception {
        JsonNode line = deliver("12000", "2000", "13900", "1076", null, null);

        assertThat(line.get("checkStatus").asText()).isEqualTo("OK");
        assertThat(line.get("receiptCheck").get("soldBetweenLitres").decimalValue()).isEqualByComparingTo("76");
        assertThat(line.get("receiptCheck").get("receivedLitres").decimalValue()).isEqualByComparingTo("11976");
        assertThat(line.get("receiptCheck").get("varianceLitres").decimalValue()).isEqualByComparingTo("-24");
        assertThat(line.get("receiptCheck").get("variancePercent").decimalValue()).isEqualByComparingTo("-0.20");
        assertThat(line.get("shortageClaimId").isNull()).isTrue();
        assertThat(currentStock()).isEqualByComparingTo("11976");
        assertThat(claimRepository.findByTankerDeliveryId(line.get("id").asLong())).isEmpty();
    }

    @Test
    void shortBeyondToleranceRaisesOneClaimCarryingEverythingTheClaimNoteNeedsAndTheLotKeepsTheInvoiceValue()
            throws Exception {
        JsonNode line = deliver("12000", "2000", "13800", "1076", "741.0", "745.0");
        long deliveryId = line.get("id").asLong();

        assertThat(line.get("checkStatus").asText()).isEqualTo("SHORT");
        assertThat(line.get("shortageClaimStatus").asText()).isEqualTo("RAISED");
        ShortageClaim claim = claimRepository.findByTankerDeliveryId(deliveryId).orElseThrow();
        assertThat(claim.getLitresShort()).isEqualByComparingTo("124");
        assertThat(claim.getClaimAmount()).isEqualByComparingTo("11160.00");
        InventoryLot lot = lotRepository.findByTankerDeliveryId(deliveryId).orElseThrow();
        assertThat(lot.getOriginalQuantity()).isEqualByComparingTo("11876");
        assertThat(lot.getCostPricePerUnit()).isEqualByComparingTo("90.940");
        assertThat(currentStock()).isEqualByComparingTo("11876");
        assertThat(shortageNotifications()).singleElement()
                .satisfies(notification -> assertThat(notification.getDedupKey())
                        .isEqualTo("DELIVERY_SHORTAGE:delivery:" + deliveryId));
        assertThat(auditActions()).contains(AuditAction.SHORTAGE_CLAIM_RAISED);

        JsonNode claimNote = json(perform(get(claimPath(claim.getId())), setup.manager()).andExpect(status().isOk()));
        assertThat(claimNote.get("shortLitres").decimalValue()).isEqualByComparingTo("124");
        assertThat(claimNote.get("shortPercent").decimalValue()).isEqualByComparingTo("1.03");
        assertThat(claimNote.get("dipBeforeLitres").decimalValue()).isEqualByComparingTo("2000");
        assertThat(claimNote.get("densityFlagged").asBoolean()).isTrue();
        assertThat(claimNote.get("nozzleReadings")).singleElement().satisfies(reading -> {
            assertThat(reading.get("readingAfter").decimalValue()).isEqualByComparingTo("1076");
            assertThat(reading.get("litresSold").decimalValue()).isEqualByComparingTo("76");
        });
        assertThat(claimNote.get("pump").get("name").asText()).isEqualTo(setup.pump().getName());
    }

    @Test
    void correctingAShortDeliveryKeepsOneClaimAndWithdrawsItOnceNoLongerShort() throws Exception {
        JsonNode line = deliver("12000", "2000", "13800", "1076", null, null);
        long deliveryId = line.get("id").asLong();

        correct(deliveryId, "12000", "INV-FIXED-" + shortId()).andExpect(status().isOk());
        JsonNode corrected = json(correct(deliveryId, "12000", "INV-AGAIN-" + shortId()).andExpect(status().isOk()));

        assertThat(corrected.get("shortageClaimId").asLong()).isEqualTo(line.get("shortageClaimId").asLong());
        assertThat(claimRepository.findAll()).filteredOn(claim -> claim.getTankerDeliveryId().equals(deliveryId)).hasSize(1);
        assertThat(shortageNotifications()).hasSize(1);

        JsonNode noLongerShort = json(correct(deliveryId, "11890", "INV-FINAL-" + shortId()).andExpect(status().isOk()));

        assertThat(noLongerShort.get("checkStatus").asText()).isEqualTo("OK");
        assertThat(noLongerShort.get("shortageClaimId").isNull()).isTrue();
        assertThat(claimRepository.findByTankerDeliveryId(deliveryId)).isEmpty();
        assertThat(currentStock()).isEqualByComparingTo("11876");
        assertThat(auditActions()).contains(AuditAction.SHORTAGE_CLAIM_WITHDRAWN);
    }

    @Test
    void overDeliveryIsShownButRaisesNoClaim() throws Exception {
        JsonNode line = deliver("5000", "0", "5100", "1000", null, null);

        assertThat(line.get("checkStatus").asText()).isEqualTo("OVER");
        assertThat(line.get("shortageClaimId").isNull()).isTrue();
        assertThat(currentStock()).isEqualByComparingTo("5100");
    }

    @Test
    void densitiesFurtherApartThanTheToleranceAreFlagged() throws Exception {
        JsonNode flagged = deliver("1000", "0", "1000", "1000", "741.9", "745.0");
        JsonNode close = deliver("1000", "1000", "2000", "1000", "744.0", "745.0");

        assertThat(flagged.get("densityFlagged").asBoolean()).isTrue();
        assertThat(flagged.get("receiptCheck").get("densityDifference").decimalValue()).isEqualByComparingTo("-3.1");
        assertThat(close.get("densityFlagged").asBoolean()).isFalse();
    }

    @Test
    void everyNozzleOnTheTankNeedsReadingsAndTheCheckItselfIsRequired() throws Exception {
        Map<String, Object> withoutReadings = new HashMap<>(lineItem("1000", "0", "1000", "1000", null, null));
        withoutReadings.put("receiptCheck", Map.of("dipBeforeLitres", 0, "dipAfterLitres", 1000));
        perform(post("/api/inventory/{pumpId}/deliveries/batch", setup.pumpId()), setup.owner(), batch(withoutReadings))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOZZLE_READINGS_MISSING"))
                .andExpect(jsonPath("$.params.nozzleNumbers").value("#1"));

        Map<String, Object> withoutCheck = new HashMap<>(lineItem("1000", "0", "1000", "1000", null, null));
        withoutCheck.remove("receiptCheck");
        perform(post("/api/inventory/{pumpId}/deliveries/batch", setup.pumpId()), setup.owner(), batch(withoutCheck))
                .andExpect(status().isBadRequest());

        perform(post("/api/inventory/{pumpId}/deliveries/batch", setup.pumpId()), setup.owner(),
                batch(lineItem("1000", "0", "1000", "999", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_AFTER_READING_BELOW_BEFORE"));
        assertThat(currentStock()).isEqualByComparingTo("0");
    }

    @Test
    void ownerSettlesAClaimWithACreditNoteThatLowersTheLotCostAndManagerCannot() throws Exception {
        long deliveryId = deliver("12000", "2000", "13800", "1076", null, null).get("id").asLong();
        long claimId = claimRepository.findByTankerDeliveryId(deliveryId).orElseThrow().getId();
        Map<String, Object> creditNote = Map.of("creditNoteAmount", new BigDecimal("10000"),
                "creditNoteReference", "CN-77", "creditNoteDate", TODAY.toString());

        perform(patch(claimPath(claimId) + "/settle"), setup.manager(), creditNote).andExpect(status().isForbidden());
        perform(patch(claimPath(claimId) + "/settle"), setup.owner(), creditNote)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.creditNoteReference").value("CN-77"));

        assertThat(lotRepository.findByTankerDeliveryId(deliveryId).orElseThrow().getCostPricePerUnit())
                .isEqualByComparingTo("90.098");
        assertThat(auditActions()).contains(AuditAction.SHORTAGE_CLAIM_SETTLED);
        perform(patch(claimPath(claimId) + "/reject"), setup.owner(), Map.of("reason", "Too late"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHORTAGE_CLAIM_NOT_RAISED"))
                .andExpect(jsonPath("$.params.statusCode").value("SETTLED"));
        correct(deliveryId, "11000", "INV-" + shortId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHORTAGE_CLAIM_CLOSED_FOR_DELIVERY"));
    }

    @Test
    void claimsListFiltersByStatusAndTotalsEveryClaim() throws Exception {
        long settled = claimIdOf(deliver("1000", "0", "990", "1000", null, null));
        long rejected = claimIdOf(deliver("1000", "990", "1980", "1000", null, null));
        long open = claimIdOf(deliver("1000", "1980", "2960", "1000", null, null));
        perform(patch(claimPath(settled) + "/settle"), setup.admin(), Map.of("creditNoteAmount", new BigDecimal("800"),
                "creditNoteReference", "CN-1", "creditNoteDate", TODAY.toString())).andExpect(status().isOk());
        perform(patch(claimPath(rejected) + "/reject"), setup.owner(), Map.of("reason", "Seal intact at delivery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").value("Seal intact at delivery"));

        JsonNode raisedOnly = json(perform(get("/api/inventory/{pumpId}/shortage-claims", setup.pumpId())
                .param("status", "RAISED"), setup.manager()).andExpect(status().isOk()));

        assertThat(raisedOnly.get("claims").get("content")).singleElement()
                .satisfies(claim -> assertThat(claim.get("id").asLong()).isEqualTo(open));
        JsonNode totals = raisedOnly.get("totals");
        assertThat(totals.get("openCount").asLong()).isEqualTo(1);
        assertThat(totals.get("openAmount").decimalValue()).isEqualByComparingTo("1800.00");
        assertThat(totals.get("settledCount").asLong()).isEqualTo(1);
        assertThat(totals.get("settledAmount").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(totals.get("rejectedCount").asLong()).isEqualTo(1);
    }

    @Test
    void deliveryRecordedBeforeReceiptChecksIsListedAsNotChecked() throws Exception {
        deliveryRepository.save(TankerDelivery.builder()
                .pumpId(setup.pumpId())
                .tankId(setup.tank().getId())
                .fuelType(FuelType.PETROL)
                .quantityDelivered(new BigDecimal("3000"))
                .costPricePerUnit(new BigDecimal("90"))
                .deliveryDate(TestDates.now().minusDays(30))
                .invoiceReference("OLD-" + shortId())
                .loggedByUserId(setup.owner().getId())
                .build());

        JsonNode deliveries = json(perform(get("/api/inventory/{pumpId}/deliveries", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));

        JsonNode old = deliveries.get("content").get(0);
        assertThat(old.get("checkStatus").asText()).isEqualTo("NOT_CHECKED");
        assertThat(old.get("receiptCheck").isNull()).isTrue();
    }

    @Test
    void claimStillRaisedAfterFifteenDaysNeedsAttention() throws Exception {
        long claimId = claimIdOf(deliver("1000", "0", "990", "1000", null, null));
        jdbcTemplate.update("UPDATE shortage_claims SET created_at = ? WHERE id = ?",
                OffsetDateTime.now().minusDays(16), claimId);

        JsonNode attention = json(perform(get("/api/pumps/{pumpId}/attention", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));

        assertThat(attention.findValuesAsText("type")).contains("SHORTAGE_CLAIMS_OPEN");
    }

    @Test
    void wetStockReconciliationCountsTheLitresReceivedNotTheLitresInvoiced() throws Exception {
        saveDip(TODAY.minusDays(1), "1000");
        deliver("12000", "1000", "12800", "1076", null, null);
        saveDip(TODAY, "12800");

        JsonNode period = json(perform(get("/api/inventory/{pumpId}/reconciliation", setup.pumpId())
                .param("from", TODAY.toString()).param("to", TODAY.toString()), setup.manager())
                .andExpect(status().isOk())).get("tanks").get(0).get("periods").get(0);

        assertThat(period.get("deliveredLitres").decimalValue()).isEqualByComparingTo("11876");
    }

    @Test
    void ownerCanWidenTheReceiptToleranceButNotBeyondFivePercent() throws Exception {
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("receiptTolerancePercent", 6))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_TOLERANCE_OUT_OF_RANGE"));
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("receiptTolerancePercent", 1, "densityTolerance", 5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptTolerancePercent").value(1.0))
                .andExpect(jsonPath("$.densityTolerance").value(5.0));

        JsonNode line = deliver("1000", "0", "990", "1000", "741.0", "745.0");

        assertThat(line.get("checkStatus").asText()).isEqualTo("OK");
        assertThat(line.get("densityFlagged").asBoolean()).isFalse();
        assertThat(line.get("receiptCheck").get("tolerancePercent").decimalValue()).isEqualByComparingTo("1.00");
    }

    private JsonNode deliver(String invoiceLitres, String dipBefore, String dipAfter, String readingAfter,
                             String observedDensity, String invoiceDensity) throws Exception {
        return json(perform(post("/api/inventory/{pumpId}/deliveries/batch", setup.pumpId()), setup.owner(),
                batch(lineItem(invoiceLitres, dipBefore, dipAfter, readingAfter, observedDensity, invoiceDensity)))
                .andExpect(status().isCreated())).get(0);
    }

    private Map<String, Object> lineItem(String invoiceLitres, String dipBefore, String dipAfter, String readingAfter,
                                         String observedDensity, String invoiceDensity) {
        Map<String, Object> check = new HashMap<>();
        check.put("dipBeforeLitres", new BigDecimal(dipBefore));
        check.put("dipAfterLitres", new BigDecimal(dipAfter));
        check.put("nozzleReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(),
                "readingBefore", new BigDecimal("1000"), "readingAfter", new BigDecimal(readingAfter))));
        check.put("observedDensity", observedDensity != null ? new BigDecimal(observedDensity) : null);
        check.put("invoiceDensity", invoiceDensity != null ? new BigDecimal(invoiceDensity) : null);
        return Map.of("tankId", setup.tank().getId(), "quantityDelivered", new BigDecimal(invoiceLitres),
                "costPricePerUnit", new BigDecimal("90"), "receiptCheck", check);
    }

    private Map<String, Object> batch(Map<String, Object> item) {
        return Map.of("deliveryDate", TODAY.toString(), "invoiceReference", "INV-" + shortId(), "items", List.of(item));
    }

    private ResultActions correct(long deliveryId, String invoiceLitres,
                                                                       String invoiceReference) throws Exception {
        return perform(patch("/api/inventory/{pumpId}/deliveries/{deliveryId}", setup.pumpId(), deliveryId), setup.owner(),
                Map.of("quantityDelivered", new BigDecimal(invoiceLitres), "deliveryDate", TODAY.toString(),
                        "invoiceReference", invoiceReference));
    }

    private long claimIdOf(JsonNode line) {
        return line.get("shortageClaimId").asLong();
    }

    private String claimPath(long claimId) {
        return "/api/inventory/" + setup.pumpId() + "/shortage-claims/" + claimId;
    }

    private BigDecimal currentStock() {
        return tankRepository.findById(setup.tank().getId()).orElseThrow().getCurrentStock();
    }

    private List<Notification> shortageNotifications() {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getPumpId().equals(setup.pumpId()))
                .filter(notification -> notification.getType() == NotificationType.DELIVERY_SHORTAGE)
                .toList();
    }

    private List<AuditAction> auditActions() {
        return auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()).stream()
                .map(AuditLog::getAction)
                .toList();
    }

    private void saveDip(LocalDate day, String litres) {
        dipCheckRepository.save(DipCheck.builder()
                .pumpId(setup.pumpId())
                .tankId(setup.tank().getId())
                .measuredQuantity(new BigDecimal(litres))
                .systemStock(new BigDecimal(litres))
                .checkedAt(day.atTime(23, 0).atZone(TestDates.BUSINESS_ZONE).toOffsetDateTime())
                .loggedByUserId(setup.manager().getId())
                .status(DipCheckStatus.WITHIN_TOLERANCE)
                .build());
    }
}
