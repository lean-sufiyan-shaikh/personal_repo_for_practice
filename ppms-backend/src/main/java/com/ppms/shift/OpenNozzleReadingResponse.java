package com.ppms.shift;

import java.math.BigDecimal;

/** A nozzle on an open shift, with the last meter reading known for it, for the price-change form to prefill. */
public record OpenNozzleReadingResponse(Long shiftId, Long nozzleId, Integer nozzleNumber, String duName, String fuelType,
                                        BigDecimal lastKnownReading, BigDecimal maxMeterValue) {
}
