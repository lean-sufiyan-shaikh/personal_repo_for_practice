package com.ppms.settlement;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Configures the daily settlement reminder alert for one payment type at one pump.
 *
 * One row per (pump_id, payment_type) pair — upserted via the config API.
 * When is_enabled = true and LocalTime.now(IST) >= alertTime, the SettlementReminderJob
 * fires a SETTLEMENT_REMINDER notification (dedup key prevents duplicate daily alerts).
 */
@Entity
@Table(name = "payment_settlement_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentSettlementConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "payment_type", nullable = false)
    private SettlementPaymentType paymentType;

    /**
     * IST time at which the daily settlement reminder should be sent.
     * Default 18:00 — configurable per pump and payment type.
     */
    @Column(name = "alert_time", nullable = false)
    @Builder.Default
    private LocalTime alertTime = LocalTime.of(18, 0);

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** Bank's merchant discount rate, as a percent of the gross settled. */
    @Column(name = "mdr_percent", nullable = false)
    private BigDecimal mdrPercent;

    /** GST charged on the MDR, as a percent of the MDR. */
    @Column(name = "gst_on_mdr_percent", nullable = false)
    @Builder.Default
    private BigDecimal gstOnMdrPercent = DEFAULT_GST_ON_MDR_PERCENT;

    public static final BigDecimal DEFAULT_GST_ON_MDR_PERCENT = new BigDecimal("18.00");

    /** UPI and fleet cards carry no MDR for the merchant by default; cards default to 1.8 %. */
    public static BigDecimal defaultMdrPercent(SettlementPaymentType type) {
        return type == SettlementPaymentType.CARD ? new BigDecimal("1.800") : new BigDecimal("0.000");
    }

    public static PaymentSettlementConfig defaultsFor(Long pumpId, SettlementPaymentType type) {
        return PaymentSettlementConfig.builder()
                .pumpId(pumpId)
                .paymentType(type)
                .mdrPercent(defaultMdrPercent(type))
                .build();
    }
}
