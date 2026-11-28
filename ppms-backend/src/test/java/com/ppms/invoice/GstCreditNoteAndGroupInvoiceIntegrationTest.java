package com.ppms.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillaryProductStatus;
import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.ancillary.UnitOfMeasure;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.transaction.TransactionPaymentMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A credit note on an invoiced sale gets a GST credit note in its own gapless series, its tax split mirroring the
 * invoice line; a parent can be invoiced once for its whole group.
 */
class GstCreditNoteAndGroupInvoiceIntegrationTest extends ApiIntegrationTest {

    private static final String PUMP_GSTIN = "27AAAPL1234C1ZV";
    private static final String SAME_STATE_GSTIN = "27BBBCS5678D1Z2";
    private static final LocalDate TODAY = TestDates.today();
    private static final String FINANCIAL_YEAR = InvoiceNumbering.financialYearOf(TODAY).substring(2);

    @Autowired private AncillaryProductRepository productRepository;
    @Autowired private AncillarySaleRepository saleRepository;

    private PumpSetup setup;
    private long shiftId;

    @BeforeEach
    void createPumpWithGstDetailsAndOpenShift() throws Exception {
        setup = createPumpWithStock("5000");
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(),
                Map.of("gstin", PUMP_GSTIN, "legalName", "Lakshmi Fuels Pvt Ltd", "stateName", "Maharashtra",
                        "invoicePrefix", "PMP3"))
                .andExpect(status().isOk());
        shiftId = json(perform(post("/api/pumps/{pumpId}/shifts/open", setup.pumpId()), setup.manager(),
                Map.of("duId", setup.du().getId(), "nozzleIds", List.of(setup.nozzle().getId()),
                        "operatorId", setup.operator().getId()))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    @Test
    void creditNoteOnAnInvoicedProductSaleMirrorsTheLineTaxInItsOwnConsecutiveSeries() throws Exception {
        long clientId = createClient(SAME_STATE_GSTIN, null);
        long saleId = sellProduct(clientId, "236.00", 2);
        long invoiceId = json(invoice(clientId, null).andExpect(status().isCreated())).get("id").asLong();

        JsonNode first = json(creditNote(clientId, "COUNTER_SALE", saleId, "118.00", 1).andExpect(status().isCreated()));
        JsonNode second = json(creditNote(clientId, "COUNTER_SALE", saleId, "59.00", null).andExpect(status().isCreated()));

        assertThat(first.get("gstCreditNoteNumber").asText()).isEqualTo("CN/" + FINANCIAL_YEAR + "/0001");
        assertThat(second.get("gstCreditNoteNumber").asText()).isEqualTo("CN/" + FINANCIAL_YEAR + "/0002");
        JsonNode notes = json(perform(get("/api/pumps/{pumpId}/credit-invoices/credit-notes", setup.pumpId())
                .param("clientId", String.valueOf(clientId)), setup.accountant()).andExpect(status().isOk()));
        JsonNode firstNote = notes.get(1);
        assertThat(firstNote.get("invoiceNumber").asText()).isEqualTo("PMP3/" + FINANCIAL_YEAR + "/0001");
        assertThat(firstNote.get("taxableValue").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(firstNote.get("cgst").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(firstNote.get("sgst").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(firstNote.get("igst").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(firstNote.get("total").decimalValue()).isEqualByComparingTo("118.00");
        assertThat(firstNote.get("quantity").decimalValue()).isEqualByComparingTo("1");

        perform(get("/api/pumps/{pumpId}/credit-invoices/credit-notes/{id}/print", setup.pumpId(),
                firstNote.get("id").asLong()), setup.accountant())
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CREDIT NOTE")))
                .andExpect(content().string(containsString("PMP3/" + FINANCIAL_YEAR + "/0001")));
        JsonNode gst = json(perform(get("/api/pumps/{pumpId}/reports/gst-summary", setup.pumpId())
                .param("month", YearMonth.from(TODAY).toString()), setup.accountant()).andExpect(status().isOk()));
        assertThat(gst.get("outwardCreditNotes").get("taxableValue").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(gst.get("outwardCreditNotes").get("cgst").decimalValue()).isEqualByComparingTo("13.50");
        assertThat(gst.get("outwardTaxable").get("taxableValue").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(gst.get("outwardTaxable").get("cgst").decimalValue()).isEqualByComparingTo("4.50");
        perform(post("/api/pumps/{pumpId}/credit-invoices/{id}/cancel", setup.pumpId(), invoiceId), setup.owner(),
                Map.of("reason", "Wrong client"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVOICE_HAS_CREDIT_NOTES"));
    }

    @Test
    void groupInvoiceBillsTheParentAndItsSubAccountsOnOneInvoiceNamingEachAccount() throws Exception {
        long parentId = createClient(SAME_STATE_GSTIN, null);
        long subId = createClient(null, parentId);
        sellFuel(parentId, "500.00");
        sellFuel(subId, "300.00");

        JsonNode invoice = json(invoice(parentId, true).andExpect(status().isCreated()));

        assertThat(invoice.get("coversSubAccounts").asBoolean()).isTrue();
        assertThat(invoice.get("grandTotal").decimalValue()).isEqualByComparingTo("800.00");
        JsonNode lines = json(perform(get("/api/pumps/{pumpId}/credit-invoices/{id}", setup.pumpId(),
                invoice.get("id").asLong()), setup.owner()).andExpect(status().isOk())).get("lines");
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat(line.get("accountName").isNull()).isFalse());
        invoice(subId, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVOICE_NOTHING_TO_BILL"));
    }

    private ResultActions creditNote(long clientId, String debitType, long debitId, String amount, Integer quantity)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", new BigDecimal(amount));
        body.put("reason", "Returned");
        body.put("debitType", debitType);
        body.put("debitId", debitId);
        body.put("returnedQuantity", quantity);
        return perform(post("/api/pumps/{pumpId}/credit-ledger/{clientId}/credit-notes", setup.pumpId(), clientId),
                setup.owner(), body);
    }

    private ResultActions invoice(long clientId, Boolean coverSubAccounts) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientId", clientId);
        body.put("periodFrom", TODAY.toString());
        body.put("periodTo", TODAY.toString());
        body.put("coverSubAccounts", coverSubAccounts);
        return perform(post("/api/pumps/{pumpId}/credit-invoices", setup.pumpId()), setup.owner(), body);
    }

    private long createClient(String gstin, Long parentId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", "9" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L));
        body.put("gstin", gstin);
        body.put("parentClientId", parentId);
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    private void sellFuel(long clientId, String amount) throws Exception {
        perform(post("/api/pumps/{pumpId}/shifts/{id}/credit-entries", setup.pumpId(), shiftId), setup.operator(),
                Map.of("clientName", "Client", "clientId", clientId, "amount", new BigDecimal(amount), "fuelType", "PETROL"))
                .andExpect(status().is2xxSuccessful());
    }

    private long sellProduct(long clientId, String pricePaid, int quantity) {
        AncillaryProduct product = productRepository.save(AncillaryProduct.builder()
                .pumpId(setup.pumpId())
                .name("Engine oil " + shortId())
                .packageSize(new BigDecimal("1.000"))
                .unitOfMeasure(UnitOfMeasure.LITRE)
                .currentStockUnits(0)
                .gstRatePercent(new BigDecimal("18.00"))
                .hsnCode("27101980")
                .status(AncillaryProductStatus.ACTIVE)
                .build());
        BigDecimal total = new BigDecimal(pricePaid);
        return saleRepository.save(AncillarySale.builder()
                .pumpId(setup.pumpId())
                .productId(product.getId())
                .quantityUnits(quantity)
                .sellingPricePerUnit(total.divide(new BigDecimal(quantity)))
                .totalAmount(total)
                .gstAmount(BigDecimal.ZERO.setScale(2))
                .totalWithGst(total)
                .gstRatePercent(new BigDecimal("18.00"))
                .paymentMode(TransactionPaymentMode.CREDIT)
                .clientId(clientId)
                .clientName("Client")
                .soldByUserId(setup.manager().getId())
                .saleDate(TODAY)
                .build()).getId();
    }
}
