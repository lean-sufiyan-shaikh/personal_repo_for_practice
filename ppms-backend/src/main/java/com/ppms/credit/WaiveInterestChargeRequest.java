package com.ppms.credit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WaiveInterestChargeRequest(
        @NotBlank(message = "A reason is required to waive an interest charge")
        String reason,
        @NotNull(message = "Choose whether the waived period may be charged again")
        InterestWaiverKind kind
) {}
