package com.ppms.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GST input on counter-product purchases and the month's GST summary built from it and from counter sales. */
class GstSummaryIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    private PumpSetup setup;
    private long productId;

    @BeforeEach
    void createProduct() throws Exception {
        setup = createPumpWithStock("0");
        productId = json(perform(post("/api/pumps/{pumpId}/ancillary/products", setup.pumpId()), setup.owner(),
                Map.of("name", "Engine Oil " + shortId(), "packageSize", new BigDecimal("1"),
                        "unitOfMeasure", "LITRE", "gstRatePercent", new BigDecimal("18")))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
        perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/prices", setup.pumpId(), productId), setup.owner(),
                Map.of("pricePerUnit", new BigDecimal("450.00"))).andExpect(status().is2xxSuccessful());
    }

    @Test
    void purchaseGstNeedsThePumpsGstinAndSplitsByTheSuppliersState() throws Exception {
        stockIn(gst("27ABCDE1234F1Z5", "1000.00", "18"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GST_INPUT_PUMP_GSTIN_MISSING"));
        setPumpGstin();

        stockIn(gst("27ABCDE1234F1Z5", "1000.00", "18"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cgstAmount").value(90.00))
                .andExpect(jsonPath("$.sgstAmount").value(90.00))
                .andExpect(jsonPath("$.igstAmount").value(0.00));
        stockIn(gst("29ABCDE1234F1Z5", "500.00", "12"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.igstAmount").value(60.00));
        stockIn(gst("29ABCDE1234", "500.00", "12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GSTIN_INVALID"));
        stockIn(gst("29ABCDE1234F1Z5", "500.00", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GST_INPUT_RATE_INVALID"));
    }

    @Test
    void summaryNetsCounterSalesOutputAgainstPurchaseInput() throws Exception {
        setPumpGstin();
        stockIn(gst("27ABCDE1234F1Z5", "1000.00", "18")).andExpect(status().isCreated());
        stockIn(gst("29ABCDE1234F1Z5", "500.00", "12")).andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/ancillary/sales", setup.pumpId()), setup.manager(),
                Map.of("productId", productId, "quantityUnits", 1, "sellingPricePerUnit", new BigDecimal("450.00"),
                        "paymentMode", "CASH")).andExpect(status().isCreated());

        JsonNode summary = summary(YearMonth.from(TODAY).toString(), setup.accountant());

        assertThat(summary.get("outwardFromCounterSales").get("taxableValue").decimalValue()).isEqualByComparingTo("381.36");
        assertThat(summary.get("outwardTaxable").get("cgst").decimalValue()).isEqualByComparingTo("34.32");
        assertThat(summary.get("outwardTaxable").get("sgst").decimalValue()).isEqualByComparingTo("34.32");
        assertThat(summary.get("inputTax").get("taxableValue").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(summary.get("inputTax").get("igst").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(summary.get("netTax").get("cgst").decimalValue()).isEqualByComparingTo("-55.68");
        assertThat(summary.get("netTax").get("igst").decimalValue()).isEqualByComparingTo("-60.00");
        assertThat(summary.get("nonGstOutwardFuel").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(summary.get("purchases")).hasSize(2);
        assertThat(summary.get("purchases").get(0).get("total").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(summary.get("pumpGstin").asText()).isEqualTo("27AAPFU0939F1ZV");
    }

    @Test
    void monthMustBeYearAndMonthAndOperatorsCannotReadIt() throws Exception {
        perform(get("/api/pumps/{pumpId}/reports/gst-summary", setup.pumpId()).param("month", "2026-13"), setup.owner())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GST_SUMMARY_MONTH_INVALID"));
        perform(get("/api/pumps/{pumpId}/reports/gst-summary", setup.pumpId()).param("month", "2026-11"), setup.operator())
                .andExpect(status().isForbidden());
    }

    private void setPumpGstin() throws Exception {
        perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(), Map.of("gstin", "27AAPFU0939F1ZV"))
                .andExpect(status().isOk());
    }

    private ResultActions stockIn(Map<String, Object> gst) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("quantityUnits", 10, "costPricePerUnit", new BigDecimal("100.00"),
                "deliveryDate", TODAY.toString(), "invoiceReference", "LUBE-" + shortId()));
        request.put("gst", gst);
        return perform(post("/api/pumps/{pumpId}/ancillary/products/{id}/deliveries", setup.pumpId(), productId),
                setup.manager(), request);
    }

    private static Map<String, Object> gst(String gstin, String taxable, String rate) {
        return Map.of("supplierName", "Lube Distributors", "supplierGstin", gstin, "invoiceDate", TODAY.toString(),
                "taxableValue", new BigDecimal(taxable), "gstRatePercent", new BigDecimal(rate));
    }

    private JsonNode summary(String month, com.ppms.user.User user) throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/reports/gst-summary", setup.pumpId()).param("month", month), user)
                .andExpect(status().isOk()));
    }
}
