package com.ppms.settlement;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ConfigResponse {
    private Long   id;
    private String paymentType;
    private String alertTime;   // HH:mm
    private boolean enabled;
    private BigDecimal mdrPercent;
    private BigDecimal gstOnMdrPercent;
}
