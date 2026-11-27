package com.ppms.settlement;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdateConfigRequest {
    @NotNull(message = "alertTime is required (HH:mm).")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime alertTime;
    private boolean enabled;
    /** Left unchanged when omitted. */
    @DecimalMin(value = "0.000", message = "mdrPercent must be between 0 and 10.")
    @DecimalMax(value = "10.000", message = "mdrPercent must be between 0 and 10.")
    private BigDecimal mdrPercent;
    /** Left unchanged when omitted. */
    @DecimalMin(value = "0.00", message = "gstOnMdrPercent must be between 0 and 100.")
    @DecimalMax(value = "100.00", message = "gstOnMdrPercent must be between 0 and 100.")
    private BigDecimal gstOnMdrPercent;
}
