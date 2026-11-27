package com.ppms.invoice;

import jakarta.persistence.*;
import lombok.*;

/** Next GST credit note serial for a pump in one financial year; a series of its own, apart from invoices. */
@Entity
@Table(name = "credit_note_sequences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreditNoteSequence {

    @EmbeddedId
    private InvoiceSequence.Key id;

    @Column(name = "next_number", nullable = false)
    private Integer nextNumber;
}
