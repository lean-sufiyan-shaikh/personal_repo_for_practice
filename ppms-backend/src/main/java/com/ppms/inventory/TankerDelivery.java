package com.ppms.inventory;

import com.ppms.fuel.FuelType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tanker_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TankerDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "tank_id", nullable = false)
    private Long tankId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "fuel_type", nullable = false)
    private FuelType fuelType;

    @Column(name = "quantity_delivered", nullable = false)
    private BigDecimal quantityDelivered;

    @Column(name = "cost_price_per_unit", nullable = false)
    private BigDecimal costPricePerUnit;

    @Column(name = "delivery_date", nullable = false)
    private OffsetDateTime deliveryDate;

    @Column(name = "invoice_reference", nullable = false)
    private String invoiceReference;

    /** Actual total from the physical bill (may include freight, taxes, other charges). Null when not entered. */
    @Column(name = "bill_total")
    private BigDecimal billTotal;

    /** FK to the tanker that made this delivery. Nullable — legacy rows and entries that skip tanker selection. */
    @Column(name = "tanker_id")
    private Long tankerId;

    /** Denormalised tanker name for history display; preserved even if tanker is deactivated. */
    @Column(name = "tanker_name", length = 100)
    private String tankerName;

    @Column(name = "dip_before_litres")
    private BigDecimal dipBeforeLitres;

    @Column(name = "dip_after_litres")
    private BigDecimal dipAfterLitres;

    @Column(name = "sold_between_litres")
    private BigDecimal soldBetweenLitres;

    /** Litres that really entered the tank; null for deliveries recorded before receipt checks existed. */
    @Column(name = "received_litres")
    private BigDecimal receivedLitres;

    /** Received minus invoiced litres; negative when short. */
    @Column(name = "variance_litres")
    private BigDecimal varianceLitres;

    @Column(name = "receipt_tolerance_percent")
    private BigDecimal receiptTolerancePercent;

    @Column(name = "observed_density")
    private BigDecimal observedDensity;

    @Column(name = "invoice_density")
    private BigDecimal invoiceDensity;

    @Column(name = "density_tolerance")
    private BigDecimal densityTolerance;

    @Column(name = "density_flagged", nullable = false)
    @Builder.Default
    private boolean densityFlagged = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_status", nullable = false)
    @Builder.Default
    private DeliveryCheckStatus checkStatus = DeliveryCheckStatus.NOT_CHECKED;

    @Column(name = "logged_by_user_id", nullable = false)
    private Long loggedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** What this delivery added to tank stock: the received litres when checked, else the invoiced litres. */
    public BigDecimal litresAddedToTank() {
        return receivedLitres != null ? receivedLitres : quantityDelivered;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
