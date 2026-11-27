package com.ppms.radar;

import com.ppms.shift.DiscrepancyType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A closed shift's operator and how its cash came out against the meters. */
public record ShiftOutcomeRow(Long shiftId, Long operatorId, LocalDate shiftDate, DiscrepancyType discrepancyType,
                              BigDecimal discrepancyAmount) {
}
