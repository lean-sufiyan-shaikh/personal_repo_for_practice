package com.ppms.fleet;

/**
 * The vehicle and driver a credit entry names: an approved vehicle by id, or a registration typed in.
 * Every part is optional.
 */
public record VehicleCreditInput(Long vehicleId, String registration, String driverName, String driverPhone) {}
