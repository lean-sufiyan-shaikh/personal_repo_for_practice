package com.ppms.invoice;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A numbered GST credit note for part or all of one line of an issued invoice. Its tax split mirrors that line;
 * seller and buyer are printed from the invoice, whose values are a snapshot.
 */
@Entity
@Table(name = "gst_credit_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstCreditNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "adjustment_id", nullable = false)
    private Long adjustmentId;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "invoice_line_id", nullable = false)
    private Long invoiceLineId;

    @Column(name = "credit_note_number", nullable = false, length = 16)
    private String creditNoteNumber;

    @Column(name = "financial_year", nullable = false, length = 7)
    private String financialYear;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false)
    private InvoiceLineType lineType;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "hsn_code", length = 8)
    private String hsnCode;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "taxable_value")
    private BigDecimal taxableValue;

    @Column(name = "gst_rate")
    private BigDecimal gstRate;

    @Column(name = "cgst", nullable = false)
    private BigDecimal cgst;

    @Column(name = "sgst", nullable = false)
    private BigDecimal sgst;

    @Column(name = "igst", nullable = false)
    private BigDecimal igst;

    @Column(name = "total", nullable = false)
    private BigDecimal total;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
