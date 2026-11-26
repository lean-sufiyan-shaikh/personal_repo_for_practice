package com.ppms.inventory;

import java.math.BigDecimal;

/** A nozzle whose meter readings a delivery receipt check on its tank needs; lastReading prefills "before". */
public record ReceiptNozzleResponse(
        Long nozzleId,
        Integer nozzleNumber,
        String duName,
        Long tankId,
        String fuelType,
        BigDecimal lastReading,
        BigDecimal maxMeterValue
) {}
