package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.common.exception.BusinessException;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Credit captured at shift close is checked like a credit sale during the shift, with an audited Owner/Admin override. */
class CloseShiftCreditCheckIntegrationTest extends ApiIntegrationTest {

    @Autowired private CreditAccountPolicyService policyService;
    @Autowired private CreditClientRepository clientRepository;
    @Autowired private CreditExtensionRepository extensionRepository;
    @Autowired private CreditCloseExceptionRepository closeExceptionRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private long shiftId;

    @BeforeEach
    void createPumpWithOpenShift() throws Exception {
        setup = createPumpWithStock("5000");
        JsonNode opened = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful()));
        shiftId = opened.get("id").asLong();
    }

    @Test
    void everyBlockedAccountIsNamedWithItsReasonInOneRefusal() throws Exception {
        long overLimit = createClient("Over Limit Co");
        setLimit(overLimit, "1000");
        long onHold = createClient("Held Co");
        backdate(sellOnCredit(onHold, "100.00"), 80);
        long overdue = createClient("Late Co");
        backdate(sellOnCredit(overdue, "100.00"), 40);
        long fine = createClient("Fine Co");

        assertThatThrownBy(() -> checkAtClose(Map.of(overLimit, "1500.00", onHold, "10.00", overdue, "10.00",
                fine, "10.00"), null))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_CLOSE_BLOCKED_CLIENTS");
                    assertThat(refusal.getParams()).containsEntry("overLimitClients", "Over Limit Co")
                            .containsEntry("onHoldClients", "Held Co")
                            .containsEntry("overdueClients", "Late Co");
                    assertThat(refusal.getMessage()).contains("'Held Co' is on hold", "against a limit of 1000.00")
                            .doesNotContain("Fine Co");
                });
    }

    @Test
    void closeCheckCountsAnAmountExtensionOnTopOfTheLimit() throws Exception {
        long clientId = createClient("Extended Co");
        setLimit(clientId, "1000");
        extensionRepository.save(CreditExtension.builder()
                .pumpId(setup.pumpId())
                .clientId(clientId)
                .extensionType(CreditExtensionType.AMOUNT_EXTENSION)
                .extensionAmount(new BigDecimal("1000.00"))
                .expiryDate(TestDates.today().plusDays(5))
                .grantedByUserId(setup.owner().getId())
                .reason("Season")
                .build());

        assertThatNoException().isThrownBy(() -> checkAtClose(Map.of(clientId, "1800.00"), null));
        assertThatThrownBy(() -> checkAtClose(Map.of(clientId, "2000.01"), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ownerOverrideLetsBlockedCreditThroughAndRecordsEachException() throws Exception {
        long clientId = createClient("Late Co");
        backdate(sellOnCredit(clientId, "100.00"), 40);

        assertThatThrownBy(() -> checkAtClose(Map.of(clientId, "50.00"),
                new CloseCreditOverride(shiftId, "Paid by cheque tomorrow", setup.manager())))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("CREDIT_CLOSE_OVERRIDE_OWNER_ONLY"));
        assertThatThrownBy(() -> checkAtClose(Map.of(clientId, "50.00"),
                new CloseCreditOverride(shiftId, " ", setup.owner())))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("CREDIT_CLOSE_OVERRIDE_REASON_REQUIRED"));

        checkAtClose(Map.of(clientId, "50.00"), new CloseCreditOverride(shiftId, "Paid by cheque tomorrow", setup.owner()));

        assertThat(closeExceptionRepository.findAll()).filteredOn(exception -> exception.getClientId() == clientId)
                .singleElement()
                .satisfies(exception -> {
                    assertThat(exception.getBlockedReason()).isEqualTo(CreditStatus.OVERDUE);
                    assertThat(exception.getShiftId()).isEqualTo(shiftId);
                    assertThat(exception.getAmount()).isEqualByComparingTo("50.00");
                    assertThat(exception.getAllowedByUserId()).isEqualTo(setup.owner().getId());
                    assertThat(exception.getReason()).isEqualTo("Paid by cheque tomorrow");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE pump_id = ? AND action = 'CREDIT_CLOSE_BLOCKED_ALLOWED'",
                Long.class, setup.pumpId())).isEqualTo(1L);
    }

    @Test
    void ownerOrAdminClosesWithBlockedCreditGivingAReasonWhileAManagerIsRefused() throws Exception {
        long clientId = createClient("Late Co");
        backdate(sellOnCredit(clientId, "100.00"), 40);
        String path = "/api/pumps/{pumpId}/shifts/{id}/close";

        perform(post(path, setup.pumpId(), shiftId), setup.manager(), closeWithCredit(clientId, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_CLOSE_BLOCKED_CLIENTS"));
        perform(post(path, setup.pumpId(), shiftId), setup.manager(), closeWithCredit(clientId, true, "Will pay tomorrow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_CLOSE_OVERRIDE_OWNER_ONLY"));
        perform(post(path, setup.pumpId(), shiftId), setup.admin(), closeWithCredit(clientId, true, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_CLOSE_OVERRIDE_REASON_REQUIRED"));

        perform(post(path, setup.pumpId(), shiftId), setup.admin(), closeWithCredit(clientId, true, "Will pay tomorrow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_BALANCED"));
        assertThat(closeExceptionRepository.findAll()).filteredOn(exception -> exception.getClientId() == clientId)
                .singleElement()
                .satisfies(exception -> {
                    assertThat(exception.getShiftId()).isEqualTo(shiftId);
                    assertThat(exception.getAllowedByUserId()).isEqualTo(setup.admin().getId());
                    assertThat(exception.getReason()).isEqualTo("Will pay tomorrow");
                });
    }

    private Map<String, Object> closeWithCredit(long clientId, Boolean allowBlockedCredit, String reason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        request.put("cashCollected", new BigDecimal("850.00"));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", new BigDecimal("150.00"));
        request.put("creditEntries", List.of(Map.of("clientName", "Late Co", "clientId", clientId,
                "amount", new BigDecimal("50.00"), "fuelType", "PETROL")));
        request.put("allowBlockedCredit", allowBlockedCredit);
        request.put("blockedCreditReason", reason);
        return request;
    }

    private void checkAtClose(Map<Long, String> amountByClientId, CloseCreditOverride override) {
        transactionTemplate.executeWithoutResult(status -> {
            Map<CreditClient, BigDecimal> amountByClient = new LinkedHashMap<>();
            amountByClientId.forEach((clientId, amount) ->
                    amountByClient.put(clientRepository.findById(clientId).orElseThrow(), new BigDecimal(amount)));
            policyService.validateCreditLimits(amountByClient, override);
        });
    }

    private long createClient(String name) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(),
                Map.of("name", name, "phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L)))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private void setLimit(long clientId, String limit) throws Exception {
        User owner = setup.owner();
        perform(patch("/api/pumps/{pumpId}/credit-ledger/clients/{clientId}/credit-limit", setup.pumpId(), clientId),
                owner, Map.of("creditLimit", new BigDecimal(limit), "reason", "Close check"))
                .andExpect(status().isOk());
    }

    private long sellOnCredit(long clientId, String amount) throws Exception {
        JsonNode shift = json(perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId),
                setup.operator(), Map.of("clientName", "Client", "clientId", clientId,
                        "amount", new BigDecimal(amount), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful()));
        JsonNode entries = shift.get("creditEntries");
        return entries.get(entries.size() - 1).get("id").asLong();
    }

    private void backdate(long entryId, int daysAgo) {
        jdbcTemplate.update("UPDATE shift_credit_entries SET created_at = ?, sale_date = ? WHERE id = ?",
                TestDates.now().minusDays(daysAgo), TestDates.today().minusDays(daysAgo), entryId);
    }
}
