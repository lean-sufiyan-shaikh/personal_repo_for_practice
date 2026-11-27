package com.ppms.fleet;

import jakarta.validation.constraints.NotNull;

public record UpdateVehicleStatusRequest(@NotNull(message = "Active is required") Boolean active) {}
