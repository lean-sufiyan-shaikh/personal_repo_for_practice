package com.ppms.radar;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One event behind a score; fields that do not apply to its kind are null. amount is rupees. litres is a shift's
 * litres sold or a week's variance, usualLitres the nozzle's median for the slot or the week's litres sold, percent
 * the week's variance as a share of sales. fromReading and toReading are the meter before and after an amendment, or
 * the previous shift's close and this shift's open for a jump. referenceId is the settlement, expense, cash drop,
 * credit client, amendment or previous shift the event is about.
 */
public record RadarEvidence(EvidenceKind kind, LocalDate date, BigDecimal amount, BigDecimal litres,
                            BigDecimal usualLitres, BigDecimal percent, BigDecimal fromReading, BigDecimal toReading,
                            Long shiftId, Long referenceId, String note) {

    static RadarEvidence money(EvidenceKind kind, LocalDate date, BigDecimal amount, Long shiftId, Long referenceId,
                               String note) {
        return new RadarEvidence(kind, date, amount, null, null, null, null, null, shiftId, referenceId, note);
    }

    static RadarEvidence litres(EvidenceKind kind, LocalDate date, BigDecimal litres, BigDecimal usualLitres,
                                BigDecimal percent, Long shiftId) {
        return new RadarEvidence(kind, date, null, litres, usualLitres, percent, null, null, shiftId, null, null);
    }

    static RadarEvidence reading(EvidenceKind kind, LocalDate date, BigDecimal fromReading, BigDecimal toReading,
                                 Long shiftId, Long referenceId, String note) {
        return new RadarEvidence(kind, date, null, null, null, null, fromReading, toReading, shiftId, referenceId, note);
    }
}
