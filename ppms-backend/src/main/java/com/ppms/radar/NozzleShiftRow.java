package com.ppms.radar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One nozzle's readings in one closed shift. */
public record NozzleShiftRow(Long nozzleId, Long shiftId, LocalDate shiftDate, Long shiftDefinitionId,
                             OffsetDateTime startedAt, OffsetDateTime endedAt, BigDecimal startReading,
                             BigDecimal endReading, BigDecimal unitsSold) {
}
