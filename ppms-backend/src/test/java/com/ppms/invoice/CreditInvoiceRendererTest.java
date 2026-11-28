package com.ppms.invoice;

import com.ppms.support.FixedBusinessClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditInvoiceRendererTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-28T10:00:00+05:30");

    private final CreditInvoiceRenderer renderer = new CreditInvoiceRenderer(new FixedBusinessClock(NOW));

    @Test
    void buyerWithoutGstinIsPrintedAsUnregistered() {
        String html = renderer.render(invoice().build(), List.of(fuelLine()));

        assertThat(html).contains("Unregistered").doesNotContain("watermark'>CANCELLED");
    }

    @Test
    void cancelledInvoiceIsPrintedAsCancelledWithItsReason() {
        CreditInvoice cancelled = invoice().status(CreditInvoiceStatus.CANCELLED).cancelReason("Wrong period")
                .cancelledAt(NOW).build();

        String html = renderer.render(cancelled, List.of(fuelLine()));

        assertThat(html).contains("<div class='watermark'>CANCELLED</div>").contains("Wrong period");
    }

    private static CreditInvoice.CreditInvoiceBuilder invoice() {
        BigDecimal zero = new BigDecimal("0.00");
        return CreditInvoice.builder()
                .id(100L)
                .invoiceNumber("PMP3/26-27/0001")
                .issuedOn(LocalDate.of(2026, 11, 28))
                .periodFrom(LocalDate.of(2026, 11, 1))
                .periodTo(LocalDate.of(2026, 11, 28))
                .sellerTradeName("Lakshmi Fuels")
                .sellerLegalName("Lakshmi Fuels Pvt Ltd")
                .sellerGstin("27AAAPL1234C1ZV")
                .sellerStateCode("27")
                .sellerStateName("Maharashtra")
                .buyerName("Fleet Co")
                .buyerStateCode("27")
                .placeOfSupplyStateCode("27")
                .fuelTotal(new BigDecimal("500.00"))
                .taxableTotal(zero)
                .cgstTotal(zero)
                .sgstTotal(zero)
                .igstTotal(zero)
                .grandTotal(new BigDecimal("500.00"));
    }

    private static CreditInvoiceLine fuelLine() {
        return CreditInvoiceLine.builder()
                .lineType(InvoiceLineType.FUEL)
                .saleDate(LocalDate.of(2026, 11, 2))
                .description("PETROL")
                .lineTotal(new BigDecimal("500.00"))
                .build();
    }
}
