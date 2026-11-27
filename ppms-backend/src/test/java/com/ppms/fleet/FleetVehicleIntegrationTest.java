package com.ppms.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.collection.MonthlyStatementService;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Approved vehicles per credit account, the OFF / WARN / BLOCK check on credit entries, and usage by vehicle. */
class FleetVehicleIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
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
    void registrationIsNormalisedAndUniqueAmongTheActiveVehiclesOfThePump() throws Exception {
        long clientId = createClient(null);
        long otherClientId = createClient(null);

        JsonNode added = json(addVehicle(clientId, vehicle("mh-12 ab 1234")).andExpect(status().isCreated()));
        assertThat(added.get("registration").asText()).isEqualTo("MH12AB1234");

        addVehicle(otherClientId, vehicle("MH12-AB-1234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VEHICLE_REGISTRATION_TAKEN"));
        addVehicle(clientId, vehicle("12"))
                .andExpect(jsonPath("$.code").value("VEHICLE_REGISTRATION_INVALID"));

        setActive(clientId, added.get("id").asLong(), false).andExpect(status().isOk());
        long replacementId = json(addVehicle(otherClientId, vehicle("MH12AB1234")).andExpect(status().isCreated()))
                .get("id").asLong();
        setActive(clientId, added.get("id").asLong(), true)
                .andExpect(jsonPath("$.code").value("VEHICLE_REGISTRATION_TAKEN"));
        assertThat(replacementId).isNotEqualTo(added.get("id").asLong());
    }

    @Test
    void blockRefusesAMissingOrUnknownVehicleAWrongFuelAndAnEntryPastTheMonthlyLimits() throws Exception {
        long clientId = createClient(null);
        setCheck(clientId, "BLOCK").andExpect(status().isOk());
        Map<String, Object> dieselOnly = vehicle("MH12AB1234");
        dieselOnly.put("allowedFuelTypes", List.of("DIESEL"));
        long dieselVehicle = json(addVehicle(clientId, dieselOnly)).get("id").asLong();
        Map<String, Object> limited = vehicle("MH14CD5678");
        limited.put("monthlyLitreLimit", new BigDecimal("10.00"));
        limited.put("monthlyAmountLimit", new BigDecimal("1500.00"));
        long limitedVehicle = json(addVehicle(clientId, limited)).get("id").asLong();

        sell(clientId, "100.00", null, null).andExpect(jsonPath("$.code").value("VEHICLE_REQUIRED_FOR_CREDIT"));
        sell(clientId, "100.00", null, "KA01ZZ9999").andExpect(jsonPath("$.code").value("VEHICLE_NOT_APPROVED"));
        sell(clientId, "100.00", dieselVehicle, null).andExpect(jsonPath("$.code").value("VEHICLE_FUEL_NOT_ALLOWED"));

        sell(clientId, "600.00", limitedVehicle, null).andExpect(status().is2xxSuccessful());
        sell(clientId, "500.00", limitedVehicle, null)
                .andExpect(jsonPath("$.code").value("VEHICLE_MONTHLY_LITRE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.params.used").value("6.00"))
                .andExpect(jsonPath("$.params.left").value("4.00"));
        sell(clientId, "400.00", null, "mh 14 cd 5678").andExpect(status().is2xxSuccessful());

        JsonNode entries = creditEntries();
        JsonNode typedIn = entries.get(entries.size() - 1);
        assertThat(typedIn.get("vehicleId").asLong()).isEqualTo(limitedVehicle);
        assertThat(typedIn.get("vehicleRegistration").asText()).isEqualTo("MH14CD5678");
        assertThat(typedIn.get("litres").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(typedIn.get("vehicleFlag").isNull()).isTrue();
    }

    @Test
    void blockRefusesAnUnknownVehicleAtTheCounterAndPastTheRupeeLimitAtShiftClose() throws Exception {
        long clientId = createClient(null);
        setCheck(clientId, "BLOCK").andExpect(status().isOk());
        Map<String, Object> limited = vehicle("MH12AB1234");
        limited.put("monthlyAmountLimit", new BigDecimal("500.00"));
        long vehicleId = json(addVehicle(clientId, limited)).get("id").asLong();

        sellAtTheCounter(clientId, "KA01ZZ9999")
                .andExpect(jsonPath("$.code").value("VEHICLE_NOT_APPROVED"));

        Map<String, Object> close = closing(List.of(closeEntry(clientId, "300.00", vehicleId),
                closeEntry(clientId, "300.00", vehicleId)));
        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), close)
                .andExpect(jsonPath("$.code").value("VEHICLE_MONTHLY_AMOUNT_LIMIT_EXCEEDED"));
    }

    @Test
    void warnLetsTheEntryThroughFlagsItAndNotifiesTheManagersOnceADay() throws Exception {
        long clientId = createClient(null);
        setCheck(clientId, "WARN").andExpect(status().isOk());
        Map<String, Object> dieselOnly = vehicle("MH12AB1234");
        dieselOnly.put("allowedFuelTypes", List.of("DIESEL"));
        long dieselVehicle = json(addVehicle(clientId, dieselOnly)).get("id").asLong();

        sell(clientId, "100.00", null, "KA01ZZ9999").andExpect(status().is2xxSuccessful());
        sell(clientId, "100.00", dieselVehicle, null).andExpect(status().is2xxSuccessful());

        JsonNode entries = creditEntries();
        assertThat(entries.get(0).get("vehicleFlag").asText()).isEqualTo("UNKNOWN_VEHICLE");
        assertThat(entries.get(0).get("vehicleRegistration").asText()).isEqualTo("KA01ZZ9999");
        assertThat(entries.get(1).get("vehicleFlag").asText()).isEqualTo("FUEL_NOT_ALLOWED");
        assertThat(notificationCount("VEHICLE_CREDIT_FLAGGED")).isEqualTo(1);
    }

    @Test
    void offKeepsTakingAnyVehicleWithoutFlagsOrNotifications() throws Exception {
        long clientId = createClient(null);
        addVehicle(clientId, vehicle("MH12AB1234")).andExpect(status().isCreated());

        sell(clientId, "100.00", null, "ka 01 zz 9999").andExpect(status().is2xxSuccessful());
        sell(clientId, "100.00", null, null).andExpect(status().is2xxSuccessful());

        JsonNode entries = creditEntries();
        assertThat(entries.get(0).get("vehicleRegistration").asText()).isEqualTo("KA01ZZ9999");
        assertThat(entries.get(0).get("vehicleFlag").isNull()).isTrue();
        assertThat(entries.get(0).get("vehicleId").isNull()).isTrue();
        assertThat(notificationCount("VEHICLE_CREDIT_FLAGGED")).isZero();
    }

    @Test
    void subAccountUsesItsOwnAndItsParentsVehiclesAndItsCheckIsSetOnTheParent() throws Exception {
        long parentId = createClient(null);
        long childId = createClient(parentId);
        long siblingId = createClient(parentId);
        long parentVehicle = json(addVehicle(parentId, vehicle("MH12AB1234"))).get("id").asLong();
        long siblingVehicle = json(addVehicle(siblingId, vehicle("MH14CD5678"))).get("id").asLong();
        setCheck(childId, "BLOCK").andExpect(jsonPath("$.code").value("VEHICLE_CHECK_ON_SUB_ACCOUNT"));
        setCheck(parentId, "BLOCK").andExpect(status().isOk());

        sell(childId, "100.00", parentVehicle, null).andExpect(status().is2xxSuccessful());
        sell(childId, "100.00", siblingVehicle, null).andExpect(jsonPath("$.code").value("VEHICLE_NOT_ON_ACCOUNT"));

        JsonNode parentList = json(perform(get("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicles",
                setup.pumpId(), parentId), setup.manager()).andExpect(status().isOk()));
        assertThat(parentList.get("vehicles")).hasSize(2);
        JsonNode childPicker = json(perform(get("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicle-picker",
                setup.pumpId(), childId), setup.operator()).andExpect(status().isOk()));
        assertThat(childPicker.get("vehicleCheck").asText()).isEqualTo("BLOCK");
        assertThat(childPicker.get("checkClientId").asLong()).isEqualTo(parentId);
        assertThat(childPicker.get("vehicles")).hasSize(1);
    }

    @Test
    void pickerShowsWhatIsLeftOfEachLimitThisMonthAndOnlyActiveVehicles() throws Exception {
        long clientId = createClient(null);
        Map<String, Object> limited = vehicle("MH12AB1234");
        limited.put("monthlyLitreLimit", new BigDecimal("50.00"));
        limited.put("defaultDriverName", "Ravi");
        limited.put("defaultDriverPhone", "9876543210");
        long vehicleId = json(addVehicle(clientId, limited)).get("id").asLong();
        long retiredId = json(addVehicle(clientId, vehicle("MH14CD5678"))).get("id").asLong();
        setActive(clientId, retiredId, false).andExpect(status().isOk());
        sell(clientId, "1250.00", vehicleId, null).andExpect(status().is2xxSuccessful());

        JsonNode picker = json(perform(get("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicle-picker",
                setup.pumpId(), clientId), setup.operator()).andExpect(status().isOk()));

        assertThat(picker.get("vehicleCheck").asText()).isEqualTo("OFF");
        assertThat(picker.get("vehicles")).hasSize(1);
        JsonNode offered = picker.get("vehicles").get(0);
        assertThat(offered.get("litresThisMonth").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(offered.get("litresLeft").decimalValue()).isEqualByComparingTo("37.50");
        assertThat(offered.get("amountLeft").isNull()).isTrue();
        assertThat(creditEntries().get(0).get("driverName").asText()).isEqualTo("Ravi");
        assertThat(creditEntries().get(0).get("driverPhone").asText()).isEqualTo("9876543210");
    }

    @Test
    void statementGroupsCreditByVehicleWithSubtotalsAndAnUnknownGroupMatchingOldFreeTextToTheVehicle()
            throws Exception {
        long parentId = createClient(null);
        long childId = createClient(parentId);
        long legacyEntry = sellAndReturnId(childId, "200.00", null, null);
        jdbcTemplate.update("UPDATE shift_credit_entries SET vehicle_registration = 'mh12-ab 1234' WHERE id = ?", legacyEntry);
        long vehicleId = json(addVehicle(parentId, vehicle("MH12AB1234"))).get("id").asLong();
        sell(parentId, "300.00", vehicleId, null).andExpect(status().is2xxSuccessful());
        sell(parentId, "150.00", null, "KA01ZZ9999").andExpect(status().is2xxSuccessful());

        String html = perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/statement", setup.pumpId(), parentId),
                setup.manager()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Usage by vehicle")
                .contains("Subtotal MH12AB1234</td><td class='amount'>5.00</td><td class='amount'>500.00</td>")
                .contains("Unassigned / unknown vehicle")
                .contains("Subtotal Unassigned / unknown vehicle</td><td class='amount'>1.50</td><td class='amount'>150.00</td>")
                .contains("(KA01ZZ9999)");
    }

    @Test
    void monthlyStatementSnapshotKeepsTheVehicleGrouping() throws Exception {
        long clientId = createClient(null);
        long vehicleId = json(addVehicle(clientId, vehicle("MH12AB1234"))).get("id").asLong();
        long entryId = sellAndReturnId(clientId, "300.00", vehicleId, null);
        LocalDate lastMonthDay = YearMonth.from(TestDates.today()).minusMonths(1).atDay(10);
        jdbcTemplate.update("UPDATE shift_credit_entries SET sale_date = ? WHERE id = ?", lastMonthDay, entryId);

        statementService.generateForPump(setup.pumpId(), YearMonth.from(lastMonthDay));

        String html = jdbcTemplate.queryForObject("SELECT html FROM credit_monthly_statements WHERE client_id = ?",
                String.class, clientId);
        assertThat(html).contains("Usage by vehicle").contains("Subtotal MH12AB1234");
    }

    @Test
    void portalShowsUsagePerVehicleForThisMonthAndTheMonthBefore() throws Exception {
        String phone = uniquePhone();
        long clientId = createClient(null, phone);
        long vehicleId = json(addVehicle(clientId, vehicle("MH12AB1234"))).get("id").asLong();
        sell(clientId, "300.00", vehicleId, null).andExpect(status().is2xxSuccessful());
        long lastMonthEntry = sellAndReturnId(clientId, "200.00", null, "KA01ZZ9999");
        jdbcTemplate.update("UPDATE shift_credit_entries SET sale_date = ? WHERE id = ?",
                YearMonth.from(TestDates.today()).minusMonths(1).atDay(5), lastMonthEntry);
        perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId), setup.owner(),
                Map.of("pin", "482913")).andExpect(status().isNoContent());

        JsonNode balance = json(mockMvc.perform(post("/api/public/credit-balance")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.91");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("pumpId", setup.pumpId(), "phone", phone, "pin", "482913"))))
                .andExpect(status().isOk()));

        JsonNode months = balance.get("vehicleUsage");
        assertThat(months).hasSize(2);
        assertThat(months.get(0).get("month").asText()).isEqualTo(YearMonth.from(TestDates.today()).toString());
        JsonNode thisMonth = months.get(0).get("vehicles").get(0);
        assertThat(thisMonth.get("registration").asText()).isEqualTo("MH12AB1234");
        assertThat(thisMonth.get("litres").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(thisMonth.get("amount").decimalValue()).isEqualByComparingTo("300.00");
        JsonNode lastMonth = months.get(1).get("vehicles").get(0);
        assertThat(lastMonth.get("registration").isNull()).isTrue();
        assertThat(lastMonth.get("amount").decimalValue()).isEqualByComparingTo("200.00");
    }

    @Test
    void operatorAndAccountantCannotManageVehiclesButTheOperatorReadsThePicker() throws Exception {
        long clientId = createClient(null);

        addVehicle(clientId, vehicle("MH12AB1234"), setup.operator()).andExpect(status().isForbidden());
        perform(get("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicles", setup.pumpId(), clientId),
                setup.accountant()).andExpect(status().isForbidden());
        perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicle-check", setup.pumpId(), clientId),
                setup.operator(), Map.of("vehicleCheck", "BLOCK")).andExpect(status().isForbidden());
        addVehicle(clientId, vehicle("MH12AB1234"), setup.admin()).andExpect(status().isCreated());
        perform(get("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicle-picker", setup.pumpId(), clientId),
                setup.operator()).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action = 'CLIENT_VEHICLE_SAVED'",
                Integer.class, setup.pumpId())).isEqualTo(1);
    }

    private static Map<String, Object> vehicle(String registration) {
        Map<String, Object> body = new HashMap<>();
        body.put("registration", registration);
        return body;
    }

    private ResultActions addVehicle(long clientId, Map<String, Object> body) throws Exception {
        return addVehicle(clientId, body, setup.manager());
    }

    private ResultActions addVehicle(long clientId, Map<String, Object> body, com.ppms.user.User user) throws Exception {
        return perform(post("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicles", setup.pumpId(), clientId),
                user, body);
    }

    private ResultActions setActive(long clientId, long vehicleId, boolean active) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicles/{vehicleId}/status",
                setup.pumpId(), clientId, vehicleId), setup.manager(), Map.of("active", active));
    }

    private ResultActions setCheck(long clientId, String mode) throws Exception {
        return perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/vehicle-check", setup.pumpId(), clientId),
                setup.manager(), Map.of("vehicleCheck", mode));
    }

    private ResultActions sell(long clientId, String amount, Long vehicleId, String registration) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientName", "Client");
        body.put("clientId", clientId);
        body.put("amount", new BigDecimal(amount));
        body.put("fuelType", "PETROL");
        body.put("vehicleId", vehicleId);
        body.put("vehicleRegistration", registration);
        return perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), body);
    }

    private long sellAndReturnId(long clientId, String amount, Long vehicleId, String registration) throws Exception {
        JsonNode entries = json(sell(clientId, amount, vehicleId, registration).andExpect(status().is2xxSuccessful()))
                .get("creditEntries");
        return entries.get(entries.size() - 1).get("id").asLong();
    }

    private JsonNode creditEntries() throws Exception {
        JsonNode shifts = json(perform(get("/api/pumps/{pumpId}/shifts/active", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        for (JsonNode shift : shifts) {
            if (shift.get("id").asLong() == shiftId) {
                return shift.get("creditEntries");
            }
        }
        throw new AssertionError("Shift " + shiftId + " missing from the active shifts");
    }

    private ResultActions sellAtTheCounter(long clientId, String registration) throws Exception {
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
                        "paymentMode", "CREDIT", "clientId", clientId, "vehicleRegistration", registration));
    }

    private Map<String, Object> closing(List<Map<String, Object>> creditEntries) {
        BigDecimal creditTotal = creditEntries.stream().map(entry -> (BigDecimal) entry.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> request = new HashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        request.put("cashCollected", new BigDecimal("1000.00").subtract(creditTotal));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", creditTotal);
        request.put("creditEntries", creditEntries);
        return request;
    }

    private static Map<String, Object> closeEntry(long clientId, String amount, long vehicleId) {
        return Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal(amount),
                "fuelType", "PETROL", "vehicleId", vehicleId);
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
}
