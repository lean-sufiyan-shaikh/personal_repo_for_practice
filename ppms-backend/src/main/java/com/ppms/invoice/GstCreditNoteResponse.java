package com.ppms.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GstCreditNoteResponse(Long id, Long clientId, Long adjustmentId, String creditNoteNumber,
                                    LocalDate issuedOn, Long invoiceId, String invoiceNumber, String reason,
                                    String description, BigDecimal quantity, BigDecimal taxableValue,
                                    BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total) {

    public static GstCreditNoteResponse of(GstCreditNote note, String invoiceNumber) {
        return new GstCreditNoteResponse(note.getId(), note.getClientId(), note.getAdjustmentId(),
                note.getCreditNoteNumber(), note.getIssuedOn(), note.getInvoiceId(), invoiceNumber, note.getReason(),
                note.getDescription(), note.getQuantity(), note.getTaxableValue(), note.getCgst(), note.getSgst(),
                note.getIgst(), note.getTotal());
    }
}
