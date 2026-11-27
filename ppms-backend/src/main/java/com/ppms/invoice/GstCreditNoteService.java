package com.ppms.invoice;

import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditAdjustment;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Issues the GST credit note for a credit note whose sale is on an issued invoice. The note credits that invoice line,
 * splitting its amount between taxable value and tax in the line's own proportion and in the line's CGST/SGST or IGST.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GstCreditNoteService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final CreditInvoiceLineRepository lineRepository;
    private final CreditInvoiceRepository invoiceRepository;
    private final CreditNoteSequenceRepository sequenceRepository;
    private final GstCreditNoteRepository creditNoteRepository;
    private final CreditClientAccessService clientAccessService;
    private final BusinessClock businessClock;

    /** Empty when the credited sale is not on an issued invoice. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<GstCreditNote> issueIfInvoiced(CreditAdjustment adjustment, User issuedBy) {
        InvoiceLineType lineType = switch (adjustment.getDebitType()) {
            case SALE -> InvoiceLineType.FUEL;
            case COUNTER_SALE -> InvoiceLineType.ANCILLARY;
            case INTEREST, BOUNCE_CHARGE -> null;
        };
        if (lineType == null) {
            return Optional.empty();
        }
        Optional<CreditInvoiceLine> invoiceLine = lineRepository.findActiveLineOfSource(lineType, adjustment.getDebitId());
        if (invoiceLine.isEmpty()) {
            return Optional.empty();
        }
        CreditInvoiceLine line = invoiceLine.get();
        LocalDate issuedOn = businessClock.today();
        String financialYear = InvoiceNumbering.financialYearOf(issuedOn);
        CreditNoteSequence sequence = lockSequence(adjustment.getPumpId(), financialYear);
        CreditedTax tax = creditedTax(line, adjustment.getAmount());

        GstCreditNote note = creditNoteRepository.save(GstCreditNote.builder()
                .pumpId(adjustment.getPumpId())
                .clientId(adjustment.getClientId())
                .adjustmentId(adjustment.getId())
                .invoiceId(line.getInvoiceId())
                .invoiceLineId(line.getId())
                .creditNoteNumber(InvoiceNumbering.creditNoteNumber(financialYear, sequence.getNextNumber()))
                .financialYear(financialYear)
                .sequenceNumber(sequence.getNextNumber())
                .issuedOn(issuedOn)
                .reason(adjustment.getReason())
                .lineType(lineType)
                .description(line.getDescription())
                .hsnCode(line.getHsnCode())
                .quantity(adjustment.getReturnedQuantity())
                .taxableValue(tax.taxableValue())
                .gstRate(line.getGstRate())
                .cgst(tax.cgst())
                .sgst(tax.sgst())
                .igst(tax.igst())
                .total(adjustment.getAmount().setScale(2, RoundingMode.HALF_UP))
                .createdBy(issuedBy.getId())
                .build());
        sequence.setNextNumber(sequence.getNextNumber() + 1);
        log.info("GST credit note issued: pump={}, number={}, invoiceLine={}, total={}",
                note.getPumpId(), note.getCreditNoteNumber(), line.getId(), note.getTotal());
        return Optional.of(note);
    }

    @Transactional(readOnly = true)
    public List<GstCreditNote> listCreditNotes(Long pumpId, Long clientId) {
        if (clientId == null) {
            return creditNoteRepository.findByPumpIdOrderByIssuedOnDescIdDesc(pumpId);
        }
        clientAccessService.requireClientForPump(pumpId, clientId);
        return creditNoteRepository.findByPumpIdAndClientIdOrderByIssuedOnDescIdDesc(pumpId, clientId);
    }

    @Transactional(readOnly = true)
    public GstCreditNote requireCreditNoteForPump(Long pumpId, Long creditNoteId) {
        return creditNoteRepository.findByIdAndPumpId(creditNoteId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_NOTE, "Credit note not found"));
    }

    @Transactional(readOnly = true)
    public CreditInvoice invoiceOf(GstCreditNote note) {
        return invoiceRepository.findById(note.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.INVOICE, "Invoice not found"));
    }

    record CreditedTax(BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {}

    /** Fuel is outside GST; a product line's credit keeps the line's own share of tax and its kind of tax. */
    static CreditedTax creditedTax(CreditInvoiceLine line, BigDecimal amount) {
        BigDecimal credited = amount.setScale(2, RoundingMode.HALF_UP);
        if (line.getLineType() == InvoiceLineType.FUEL || line.getTaxableValue() == null) {
            return new CreditedTax(null, ZERO, ZERO, ZERO);
        }
        BigDecimal taxable = credited.multiply(line.getTaxableValue())
                .divide(line.getLineTotal(), 2, RoundingMode.HALF_UP);
        BigDecimal gst = credited.subtract(taxable);
        GstTaxSplit split = GstTaxSplit.of(gst, line.getIgst().signum() == 0);
        return new CreditedTax(taxable, split.cgst(), split.sgst(), split.igst());
    }

    private CreditNoteSequence lockSequence(Long pumpId, String financialYear) {
        sequenceRepository.createIfMissing(pumpId, financialYear);
        return sequenceRepository.findByIdForUpdate(new InvoiceSequence.Key(pumpId, financialYear))
                .orElseThrow(() -> new IllegalStateException(
                        "Credit note sequence missing after insert for pump " + pumpId + " year " + financialYear));
    }
}
