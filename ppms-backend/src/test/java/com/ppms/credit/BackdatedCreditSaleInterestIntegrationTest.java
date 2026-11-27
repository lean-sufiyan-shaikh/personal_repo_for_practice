package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.fuel.FuelType;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A credit sale dated inside a period whose interest is already charged re-charges that period, however the sale
 * arrives: a back-dated counter sale, a shift closed or backfilled for a past date, or an old unlinked entry linked
 * to the client. Interest is 3% a month (0.1% a day); the charged period is the ten days before today.
 */
class BackdatedCreditSaleInterestIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();
    private static final LocalDate SALE_DATE = TODAY.minusDays(5);

    @Autowired private CreditClientRepository clientRepository;
    @Autowired private CreditInterestChargeRepository interestChargeRepository;
    @Autowired private CreditPaymentRepository paymentRepository;
    @Autowired private ShiftCreditEntryRepository creditEntryRepository;
    @Autowired private CreditInterestService interestService;
    @Autowired private CreditLedgerQueryService ledgerQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpSetup setup;
    private CreditClient client;
    private CreditInterestCharge chargedPeriod;

    @BeforeEach
    void createClientWithChargedInterest() throws Exception {
        setup = createPumpWithStock("0");
        recordDelivery(setup, "5000", "90.00", TODAY.minusDays(20));
        client = saveClient(null);
        chargedPeriod = seedCharge(client, TODAY.minusDays(10), TODAY.minusDays(1));
    }

    @Test
    void backdatedCounterSaleOnCreditRechargesThePeriod() throws Exception {
        long productId = productWithPriceAndStockFrom(TODAY.minusDays(10));

        perform(post("/api/pumps/{pumpId}/ancillary/sales/backfill", setup.pumpId()), setup.owner(),
                Map.of("productId", productId, "saleDate", SALE_DATE.toString(), "quantityUnits", 2,
                        "paymentMode", "CREDIT", "clientId", client.getId()))
                .andExpect(status().is2xxSuccessful());

        BigDecimal saleTotal = jdbcTemplate.queryForObject(
                "SELECT total_with_gst FROM ancillary_sales WHERE client_id = ?", BigDecimal.class, client.getId());
        assertRechargedFor(saleTotal);
    }

    @Test
    void backfilledShiftWithCreditRechargesThePeriod() throws Exception {
        perform(post("/api/pumps/{pumpId}/shifts/backfill", setup.pumpId()), setup.owner(), backfillWithCredit("300.00"))
                .andExpect(status().is2xxSuccessful());

        assertRechargedFor(new BigDecimal("300.00"));
    }

    @Test
    void shiftClosedForAPastDateWithCreditRechargesThePeriod() throws Exception {
        long shiftId = openShift();
        jdbcTemplate.update("UPDATE shifts SET shift_date = ? WHERE id = ?", SALE_DATE, shiftId);
        Map<String, Object> close = new HashMap<>();
        close.put("fuelReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(), "endReading", new BigDecimal("1010.000"))));
        close.put("cashCollected", new BigDecimal("700.00"));
        close.put("upiCollected", BigDecimal.ZERO);
        close.put("cardCollected", BigDecimal.ZERO);
        close.put("fleetCardCollected", BigDecimal.ZERO);
        close.put("creditTotal", new BigDecimal("300.00"));
        close.put("creditEntries", List.of(Map.of("clientName", client.getName(), "clientId", client.getId(),
                "amount", new BigDecimal("300.00"), "fuelType", "PETROL")));

        perform(post("/api/pumps/{pumpId}/shifts/{id}/close", setup.pumpId(), shiftId), setup.manager(), close)
                .andExpect(status().is2xxSuccessful());

        assertRechargedFor(new BigDecimal("300.00"));
    }

    @Test
    void linkingAnOldUnlinkedCreditEntryRechargesThePeriod() throws Exception {
        long shiftId = openShift();
        ShiftCreditEntry unlinked = creditEntryRepository.save(ShiftCreditEntry.builder()
                .shiftId(shiftId)
                .clientName("Free text name")
                .fuelType(FuelType.PETROL.name())
                .amount(new BigDecimal("300.00"))
                .saleDate(SALE_DATE)
                .build());

        perform(post("/api/pumps/{pumpId}/credit-ledger/entries/{entryId}/reassign", setup.pumpId(), unlinked.getId()),
                setup.owner(), Map.of("toClientId", client.getId(), "reason", "Belongs to this fleet"))
                .andExpect(status().is2xxSuccessful());

        assertRechargedFor(new BigDecimal("300.00"));
    }

    @Test
    void saleOnASubAccountRechargesTheParentsPeriod() throws Exception {
        CreditClient child = saveClient(client.getId());

        perform(post("/api/pumps/{pumpId}/shifts/backfill", setup.pumpId()), setup.owner(),
                backfillWithCredit(child, "300.00"))
                .andExpect(status().is2xxSuccessful());

        assertRechargedFor(new BigDecimal("300.00"));
    }

    @Test
    void saleAfterTheLastChargedDayLeavesTheChargeAlone() throws Exception {
        long shiftId = openShift();
        ShiftCreditEntry unlinked = creditEntryRepository.save(ShiftCreditEntry.builder()
                .shiftId(shiftId)
                .clientName("Free text name")
                .fuelType(FuelType.PETROL.name())
                .amount(new BigDecimal("300.00"))
                .saleDate(TODAY)
                .build());

        perform(post("/api/pumps/{pumpId}/credit-ledger/entries/{entryId}/reassign", setup.pumpId(), unlinked.getId()),
                setup.owner(), Map.of("toClientId", client.getId(), "reason", "Belongs to this fleet"))
                .andExpect(status().is2xxSuccessful());

        assertThat(interestChargeRepository.findById(chargedPeriod.getId()).orElseThrow().getVoidedAt()).isNull();
        assertThat(interestChargeRepository.findByClientIdOrderByCreatedAtDesc(client.getId())).hasSize(1);
    }

    @Test
    void interestRecoveredCountsPaymentsOfEveryAccountOfTheGroupButNotReversedOnes() {
        CreditClient child = saveClient(client.getId());
        savePayment(child, "0.60", TODAY, ExpenseApprovalStatus.APPROVED);
        savePayment(child, "0.30", TODAY, ExpenseApprovalStatus.REVERSED);
        savePayment(client, "0.20", TODAY, ExpenseApprovalStatus.APPROVED);

        Map<Long, CreditInterestService.InterestBreakdown> breakdown =
                interestService.computeInterestBreakdownBatch(List.of(client.getId(), child.getId()));

        assertThat(breakdown.get(client.getId()).totalInterestRecovered()).isEqualByComparingTo("0.80");
        assertThat(breakdown.get(client.getId()).outstandingInterest()).isEqualByComparingTo("0.20");
        assertThat(breakdown.get(child.getId()).totalInterestRecovered()).isEqualByComparingTo("0.00");
        assertThat(interestService.computeTotalInterestRecovered(client.getId())).isEqualByComparingTo("0.80");
        CreditClientResponse parentRow = ledgerQueryService.toClientResponse(client);
        assertThat(parentRow.getTotalInterestRecovered()).isEqualByComparingTo("0.80");
        assertThat(parentRow.getOutstandingInterest()).isEqualByComparingTo("0.20");
    }

    @Test
    void paymentMadeBeforeTheChargedPeriodEndedDoesNotCountAsRecoveringIt() {
        savePayment(client, "1.00", TODAY.minusDays(2), ExpenseApprovalStatus.APPROVED);

        assertThat(interestService.computeTotalInterestRecovered(client.getId())).isEqualByComparingTo("0.00");
    }

    @Test
    void interestRowIsDatedByTheLastDayItCovers() {
        List<CreditTransactionResponse> rows = ledgerQueryService.getAllTransactions(client, false);

        assertThat(rows).filteredOn(row -> "INTEREST".equals(row.getType()))
                .singleElement()
                .satisfies(row -> assertThat(row.getBusinessDate()).isEqualTo(TODAY.minusDays(1)));
    }

    private void assertRechargedFor(BigDecimal saleAmount) {
        CreditInterestCharge waived = interestChargeRepository.findById(chargedPeriod.getId()).orElseThrow();
        assertThat(waived.getVoidedAt()).isNotNull();
        assertThat(waived.getWaiverKind()).isEqualTo(InterestWaiverKind.RECALCULATE);
        // Five days (the sale date to yesterday) at 0.1% a day of the sale.
        BigDecimal expected = saleAmount.multiply(new BigDecimal("0.005")).setScale(2, RoundingMode.HALF_UP);
        assertThat(interestChargeRepository.findByClientIdInAndVoidedAtIsNull(List.of(client.getId())))
                .singleElement()
                .satisfies(charge -> {
                    assertThat(charge.getAmount()).isEqualByComparingTo(expected);
                    assertThat(charge.getPeriodFrom()).isEqualTo(TODAY.minusDays(10));
                    assertThat(charge.getPeriodTo()).isEqualTo(TODAY.minusDays(1));
                    assertThat(charge.getSource()).isEqualTo("RECALCULATED");
                });
    }

    private long openShift() throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private Map<String, Object> backfillWithCredit(String creditAmount) {
        return backfillWithCredit(client, creditAmount);
    }

    private Map<String, Object> backfillWithCredit(CreditClient account, String creditAmount) {
        Map<String, Object> request = new HashMap<>();
        request.put("shiftDefinitionId", setup.allDayShift().getId());
        request.put("shiftDate", SALE_DATE.toString());
        request.put("duId", setup.du().getId());
        request.put("operatorId", setup.operator().getId());
        request.put("nozzleReadings", List.of(Map.of("nozzleId", setup.nozzle().getId(),
                "openingReading", new BigDecimal("1000.000"), "closingReading", new BigDecimal("1010.000"))));
        request.put("cashCollected", new BigDecimal("950.00").subtract(new BigDecimal(creditAmount)));
        request.put("upiCollected", BigDecimal.ZERO);
        request.put("cardCollected", BigDecimal.ZERO);
        request.put("fleetCardCollected", BigDecimal.ZERO);
        request.put("creditTotal", new BigDecimal(creditAmount));
        request.put("creditEntries", List.of(Map.of("clientName", account.getName(), "clientId", account.getId(),
                "amount", new BigDecimal(creditAmount), "fuelType", "PETROL")));
        request.put("fuelRateOverrides", Map.of("PETROL", new BigDecimal("95.00")));
        return request;
    }

    private long productWithPriceAndStockFrom(LocalDate from) throws Exception {
        JsonNode product = json(perform(post("/api/pumps/{pumpId}/ancillary/products", setup.pumpId()), setup.owner(),
                Map.of("name", "Engine Oil " + shortId(), "packageSize", new BigDecimal("1"), "unitOfMeasure", "LITRE",
                        "gstRatePercent", new BigDecimal("18")))
                .andExpect(status().is2xxSuccessful()));
        long productId = product.get("id").asLong();
        perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/prices", setup.pumpId(), productId), setup.owner(),
                Map.of("pricePerUnit", new BigDecimal("450.00"))).andExpect(status().is2xxSuccessful());
        perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/deliveries", setup.pumpId(), productId), setup.manager(),
                Map.of("quantityUnits", 5, "costPricePerUnit", new BigDecimal("300.00"),
                        "deliveryDate", from.toString(), "invoiceReference", "AN-" + shortId()))
                .andExpect(status().is2xxSuccessful());
        jdbcTemplate.update("UPDATE ancillary_product_prices SET effective_from = effective_from - INTERVAL '15 days' "
                + "WHERE product_id = ?", productId);
        return productId;
    }

    private CreditClient saveClient(Long parentClientId) {
        return clientRepository.save(CreditClient.builder()
                .pumpId(setup.pumpId())
                .name("Client " + shortId())
                .parentClientId(parentClientId)
                .creditLimit(new BigDecimal("100000.00"))
                .monthlyInterestRate(parentClientId == null ? new BigDecimal("3.00") : BigDecimal.ZERO)
                .build());
    }

    private CreditInterestCharge seedCharge(CreditClient account, LocalDate periodFrom, LocalDate periodTo) {
        return interestChargeRepository.save(CreditInterestCharge.builder()
                .pumpId(setup.pumpId())
                .clientId(account.getId())
                .amount(new BigDecimal("1.00"))
                .outstandingBalance(BigDecimal.ZERO)
                .rateApplied(new BigDecimal("3.00"))
                .daysApplied(10)
                .periodFrom(periodFrom)
                .periodTo(periodTo)
                .source("TEST_SEED")
                .build());
    }

    private void savePayment(CreditClient account, String amount, LocalDate paidAt, ExpenseApprovalStatus status) {
        paymentRepository.save(CreditPayment.builder()
                .pumpId(setup.pumpId())
                .clientId(account.getId())
                .amount(new BigDecimal(amount))
                .paymentMode(PaymentMode.CASH)
                .paidAt(paidAt)
                .recordedById(setup.owner().getId())
                .paymentApprovalStatus(status)
                .build());
    }
}
