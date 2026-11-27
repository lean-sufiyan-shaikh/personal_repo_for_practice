package com.ppms.ancillary;

import com.ppms.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseGstTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final String PUMP_GSTIN = "27AAPFU0939F1ZV";

    @Test
    void supplierInThePumpsStateChargesCgstAndSgstWithTheOddPaisaOnCgst() {
        PurchaseGst gst = PurchaseGst.of(request("27ABCDE1234F1Z5", "1000.05", "18"), PUMP_GSTIN, "B-1", TODAY);

        assertThat(gst.split().cgst()).isEqualByComparingTo("90.01");
        assertThat(gst.split().sgst()).isEqualByComparingTo("90.00");
        assertThat(gst.split().igst()).isEqualByComparingTo("0.00");
        assertThat(gst.totalGst()).isEqualByComparingTo("180.01");
    }

    @Test
    void supplierInAnotherStateChargesIgst() {
        PurchaseGst gst = PurchaseGst.of(request("29ABCDE1234F1Z5", "500", "12"), PUMP_GSTIN, "B-2", TODAY);

        assertThat(gst.split().igst()).isEqualByComparingTo("60.00");
        assertThat(gst.split().cgst()).isEqualByComparingTo("0.00");
        assertThat(gst.supplierGstin()).isEqualTo("29ABCDE1234F1Z5");
    }

    @Test
    void invalidGstinRateMissingPumpGstinMissingInvoiceNumberAndFutureDateAreRefused() {
        assertCode(() -> PurchaseGst.of(request("27ABCDE1234", "100", "18"), PUMP_GSTIN, "B", TODAY), "GSTIN_INVALID");
        assertCode(() -> PurchaseGst.of(request("27ABCDE1234F1Z5", "100", "15"), PUMP_GSTIN, "B", TODAY),
                "GST_INPUT_RATE_INVALID");
        assertCode(() -> PurchaseGst.of(request("27ABCDE1234F1Z5", "100", "18"), null, "B", TODAY),
                "GST_INPUT_PUMP_GSTIN_MISSING");
        assertCode(() -> PurchaseGst.of(request("27ABCDE1234F1Z5", "100", "18"), PUMP_GSTIN, null, TODAY),
                "GST_INPUT_INVOICE_NUMBER_REQUIRED");
        assertCode(() -> PurchaseGst.of(new PurchaseGstRequest("Acme", "27ABCDE1234F1Z5", TODAY.plusDays(1),
                new BigDecimal("100"), new BigDecimal("5")), PUMP_GSTIN, "B", TODAY), "GST_INPUT_INVOICE_DATE_IN_FUTURE");
    }

    private static PurchaseGstRequest request(String gstin, String taxable, String rate) {
        return new PurchaseGstRequest(" Acme Lubes ", gstin, TODAY, new BigDecimal(taxable), new BigDecimal(rate));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(code));
    }
}
