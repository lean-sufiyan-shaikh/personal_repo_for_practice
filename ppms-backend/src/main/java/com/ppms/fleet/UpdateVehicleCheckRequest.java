package com.ppms.fleet;

import com.ppms.credit.VehicleCheckMode;
import jakarta.validation.constraints.NotNull;

public record UpdateVehicleCheckRequest(@NotNull(message = "Vehicle check is required") VehicleCheckMode vehicleCheck) {}
