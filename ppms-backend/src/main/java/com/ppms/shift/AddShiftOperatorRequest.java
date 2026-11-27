package com.ppms.shift;

import jakarta.validation.constraints.NotNull;

public record AddShiftOperatorRequest(@NotNull(message = "Operator is required") Long operatorId) {}
