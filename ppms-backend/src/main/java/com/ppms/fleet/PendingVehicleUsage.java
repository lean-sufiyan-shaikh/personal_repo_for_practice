package com.ppms.fleet;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Credit already let through for each vehicle earlier in the same request, not yet saved, so the entries closed
 * with one shift are checked against a vehicle's monthly limit together.
 */
public final class PendingVehicleUsage {

    private final Map<Long, BigDecimal> litresByVehicleId = new HashMap<>();
    private final Map<Long, BigDecimal> amountByVehicleId = new HashMap<>();

    BigDecimal litresOf(Long vehicleId) {
        return litresByVehicleId.getOrDefault(vehicleId, BigDecimal.ZERO);
    }

    BigDecimal amountOf(Long vehicleId) {
        return amountByVehicleId.getOrDefault(vehicleId, BigDecimal.ZERO);
    }

    void add(Long vehicleId, BigDecimal litres, BigDecimal amount) {
        litresByVehicleId.merge(vehicleId, litres, BigDecimal::add);
        amountByVehicleId.merge(vehicleId, amount, BigDecimal::add);
    }
}
