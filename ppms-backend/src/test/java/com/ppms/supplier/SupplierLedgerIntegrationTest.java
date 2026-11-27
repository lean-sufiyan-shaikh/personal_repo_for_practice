package com.ppms.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.inventory.ShortageClaim;
import com.ppms.inventory.ShortageClaimRepository;
import com.ppms.inventory.ShortageClaimStatus;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tanker deliveries booked to suppliers, and each supplier's balance from deliveries, payments and credit notes. */
class SupplierLedgerIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private TankerDeliveryRepository deliveryRepository;
    @Autowired private ShortageClaimRepository claimRepository;

    private PumpSetup setup;
    private long iocl;
    private long bpcl;

    @BeforeEach
    void createSuppliers() throws Exception {
        setup = createPumpWithStock("0");
        iocl = createSupplier("IOCL " + shortId());
        bpcl = createSupplier("BPCL " + shortId());
    }

    @Test
    void deliveryTakesThePickedSupplierElseTheDefaultElseNone() throws Exception {
        assertThat(delivery(null, "1000", TODAY.minusDays(3)).get("supplierId").isNull()).isTrue();

        perform(post("/api/pumps/{pumpId}/suppliers/{id}/default", setup.pumpId(), bpcl), setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultSupplier").value(true));
        JsonNode defaulted = delivery(null, "1000", TODAY.minusDays(2));
        assertThat(defaulted.get("supplierId").asLong()).isEqualTo(bpcl);
        assertThat(defaulted.get("supplierName").asText()).startsWith("BPCL");

        perform(post("/api/pumps/{pumpId}/suppliers/{id}/default", setup.pumpId(), iocl), setup.admin())
                .andExpect(status().isOk());
        assertThat(delivery(bpcl, "1000", TODAY.minusDays(1)).get("supplierId").asLong()).isEqualTo(bpcl);
        assertThat(delivery(null, "500", TODAY.minusDays(1)).get("supplierId").asLong()).isEqualTo(iocl);

        JsonNode balances = ledgerList();
        assertThat(balances.get("unassigned").get("invoiceCount").asInt()).isEqualTo(1);
        assertThat(balances.get("unassigned").get("amount").decimalValue()).isEqualByComparingTo("90000.00");
        assertThat(balances.get("totalOutstanding").decimalValue()).isEqualByComparingTo("225000.00");

        JsonNode history = json(perform(get("/api/inventory/{pumpId}/deliveries", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
        assertThat(history.get("content").findValuesAsText("supplierName")).anyMatch(name -> name.startsWith("IOCL"));
    }

    @Test
    void ledgerRunsFromOpeningBalanceThroughDeliveriesPaymentsAndCreditNotes() throws Exception {
        Map<String, Object> update = new HashMap<>(Map.of("name", "IOCL", "openingBalance", new BigDecimal("10000")));
        perform(put("/api/pumps/{pumpId}/suppliers/{id}", setup.pumpId(), iocl), setup.owner(), update)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_OPENING_BALANCE_DATE_REQUIRED"));
        update.put("openingBalanceDate", TODAY.minusDays(90).toString());
        perform(put("/api/pumps/{pumpId}/suppliers/{id}", setup.pumpId(), iocl), setup.owner(), update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(10000.00));

        long deliveryId = delivery(iocl, "1000", TODAY.minusDays(40)).get("id").asLong();
        settleClaimOn(deliveryId, "5000.00", TODAY.minusDays(30));
        delivery(iocl, "500", TODAY.minusDays(5));
        long paymentId = pay(iocl, "50000.00", TODAY.minusDays(10)).get("id").asLong();
        long voidedId = pay(iocl, "7000.00", TODAY.minusDays(9)).get("id").asLong();
        perform(post("/api/pumps/{pumpId}/supplier-payments/{id}/void", setup.pumpId(), voidedId), setup.owner(),
                Map.of("reason", "Bounced")).andExpect(status().isOk());

        JsonNode ledger = json(perform(get("/api/pumps/{pumpId}/supplier-ledger/{id}", setup.pumpId(), iocl), setup.manager())
                .andExpect(status().isOk()));
        assertThat(ledger.get("entries").findValuesAsText("type"))
                .containsExactly("OPENING_BALANCE", "DELIVERY", "CREDIT_NOTE", "PAYMENT", "DELIVERY");
        assertThat(ledger.get("entries").get(3).get("paymentId").asLong()).isEqualTo(paymentId);
        assertThat(ledger.get("balance").decimalValue()).isEqualByComparingTo("90000.00");
        assertThat(ledger.get("aging").get("days31To60").decimalValue()).isEqualByComparingTo("45000.00");
        assertThat(ledger.get("aging").get("days0To15").decimalValue()).isEqualByComparingTo("45000.00");

        pay(iocl, "110000.00", TODAY);
        JsonNode iocLine = ledgerList().get("suppliers").findParents("supplierId").stream()
                .filter(line -> line.get("supplierId").asLong() == iocl).findFirst().orElseThrow();
        assertThat(iocLine.get("outstanding").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(iocLine.get("advance").decimalValue()).isEqualByComparingTo("20000.00");
        assertThat(iocLine.get("lastPaymentDate").asText()).isEqualTo(TODAY.toString());
    }

    @Test
    void managerReadsSuppliersAndTheLedgerButOnlyOwnerOrAdminPays() throws Exception {
        perform(get("/api/pumps/{pumpId}/suppliers", setup.pumpId()), setup.manager()).andExpect(status().isOk());
        perform(get("/api/pumps/{pumpId}/supplier-payments", setup.pumpId()), setup.manager()).andExpect(status().isOk());
        perform(get("/api/pumps/{pumpId}/supplier-ledger", setup.pumpId()), setup.operator()).andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/supplier-payments", setup.pumpId()), setup.manager(),
                paymentBody(iocl, "100.00", TODAY)).andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/suppliers/{id}/default", setup.pumpId(), iocl), setup.manager())
                .andExpect(status().isForbidden());
        pay(iocl, "100.00", TODAY);
    }

    private long createSupplier(String name) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/suppliers", setup.pumpId()), setup.owner(), Map.of("name", name))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private JsonNode delivery(Long supplierId, String litres, LocalDate date) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("tankId", setup.tank().getId(),
                "quantityDelivered", new BigDecimal(litres),
                "costPricePerUnit", new BigDecimal("90.00"),
                "deliveryDate", date.toString(),
                "invoiceReference", "INV-" + shortId(),
                "receiptCheck", receiptCheck(setup.pumpId(), setup.tank().getId(), "0", litres)));
        if (supplierId != null) {
            request.put("supplierId", supplierId);
        }
        return json(perform(post("/api/inventory/{pumpId}/deliveries", setup.pumpId()), setup.owner(), request)
                .andExpect(status().isCreated()));
    }

    private void settleClaimOn(long deliveryId, String creditNote, LocalDate date) {
        claimRepository.save(ShortageClaim.builder()
                .pumpId(setup.pumpId())
                .tankerDeliveryId(deliveryId)
                .litresShort(new BigDecimal("50"))
                .pricePerLitre(new BigDecimal("90.00"))
                .claimAmount(new BigDecimal("4500.00"))
                .status(ShortageClaimStatus.SETTLED)
                .raisedByUserId(setup.owner().getId())
                .creditNoteAmount(new BigDecimal(creditNote))
                .creditNoteReference("CN-" + shortId())
                .creditNoteDate(date)
                .resolvedByUserId(setup.owner().getId())
                .resolvedAt(OffsetDateTime.now())
                .build());
        assertThat(deliveryRepository.findById(deliveryId)).isPresent();
    }

    private JsonNode pay(long supplierId, String amount, LocalDate date) throws Exception {
        return json(perform(post("/api/pumps/{pumpId}/supplier-payments", setup.pumpId()), setup.owner(),
                paymentBody(supplierId, amount, date)).andExpect(status().isCreated()));
    }

    private static Map<String, Object> paymentBody(long supplierId, String amount, LocalDate date) {
        return Map.of("supplierId", supplierId, "amount", new BigDecimal(amount), "paymentDate", date.toString(),
                "paymentMode", "BANK_TRANSFER");
    }

    private JsonNode ledgerList() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/supplier-ledger", setup.pumpId()), setup.manager())
                .andExpect(status().isOk()));
    }
}
