package com.ppms.fleet;

/**
 * What a credit entry records about its vehicle once checked: the approved vehicle it was matched to (null when
 * unknown), the normalised registration, the driver (defaulting to the vehicle's), and the flag a WARN account's
 * entry was let through with.
 */
public record VehicleCreditDecision(Long vehicleId, String registration, String driverName, String driverPhone,
                                    VehicleFlag flag) {}
