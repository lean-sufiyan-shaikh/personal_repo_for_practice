package com.ppms.invoice;

import com.ppms.credit.CreditAdjustment;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditDebitType;
import com.ppms.support.FixedBusinessClock;
import com.ppms.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GstCreditNoteServiceTest {

    private final CreditInvoiceLineRepository lineRepository = mock(CreditInvoiceLineRepository.class);
    private final GstCreditNoteRepository creditNoteRepository = mock(GstCreditNoteRepository.class);

    private final GstCreditNoteService service = new GstCreditNoteService(lineRepository,
            mock(CreditInvoiceRepository.class), mock(CreditNoteSequenceRepository.class), creditNoteRepository,
            mock(CreditClientAccessService.class),
            new FixedBusinessClock(OffsetDateTime.parse("2026-11-28T10:00:00+05:30")));

    @Test
    void sameStateCreditKeepsTheLinesShareOfTaxSplitIntoCgstAndSgst() {
        GstCreditNoteService.CreditedTax tax = GstCreditNoteService.creditedTax(
                productLine("200.00", "18.00", "18.00", "0.00", "236.00"), new BigDecimal("118.00"));

        assertThat(tax.taxableValue()).isEqualByComparingTo("100.00");
        assertThat(tax.cgst()).isEqualByComparingTo("9.00");
        assertThat(tax.sgst()).isEqualByComparingTo("9.00");
        assertThat(tax.igst()).isEqualByComparingTo("0.00");
    }

    @Test
    void creditOnALineChargedIgstIsAllIgst() {
        GstCreditNoteService.CreditedTax tax = GstCreditNoteService.creditedTax(
                productLine("200.00", "0.00", "0.00", "36.00", "236.00"), new BigDecimal("59.00"));

        assertThat(tax.taxableValue()).isEqualByComparingTo("50.00");
        assertThat(tax.igst()).isEqualByComparingTo("9.00");
        assertThat(tax.cgst()).isEqualByComparingTo("0.00");
        assertThat(tax.sgst()).isEqualByComparingTo("0.00");
    }

    @Test
    void creditOnAFuelLineCarriesNoGst() {
        CreditInvoiceLine fuel = CreditInvoiceLine.builder().lineType(InvoiceLineType.FUEL)
                .lineTotal(new BigDecimal("500.00")).build();

        GstCreditNoteService.CreditedTax tax = GstCreditNoteService.creditedTax(fuel, new BigDecimal("100.00"));

        assertThat(tax.taxableValue()).isNull();
        assertThat(tax.cgst().add(tax.sgst()).add(tax.igst())).isEqualByComparingTo("0");
    }

    @Test
    void creditOnASaleThatIsNotOnAnIssuedInvoiceHasNoGstCreditNote() {
        when(lineRepository.findActiveLineOfSource(InvoiceLineType.ANCILLARY, 21L)).thenReturn(Optional.empty());
        CreditAdjustment adjustment = CreditAdjustment.builder().pumpId(7L).debitType(CreditDebitType.COUNTER_SALE)
                .debitId(21L).amount(new BigDecimal("59.00")).build();

        assertThat(service.issueIfInvoiced(adjustment, new User())).isEmpty();
        verifyNoInteractions(creditNoteRepository);
    }

    private static CreditInvoiceLine productLine(String taxable, String cgst, String sgst, String igst, String total) {
        return CreditInvoiceLine.builder()
                .lineType(InvoiceLineType.ANCILLARY)
                .taxableValue(new BigDecimal(taxable))
                .cgst(new BigDecimal(cgst))
                .sgst(new BigDecimal(sgst))
                .igst(new BigDecimal(igst))
                .lineTotal(new BigDecimal(total))
                .gstRate(new BigDecimal("18.00"))
                .build();
    }
}
