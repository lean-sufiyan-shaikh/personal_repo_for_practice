package com.ppms.fleet;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;

/** A vehicle approved to take credit on a client's account. */
@Entity
@Table(name = "client_vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientVehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    /** The client (a standalone account, a parent or a sub-account) the vehicle belongs to. */
    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** Normalised by {@link VehicleRegistrationNumber}. */
    @Column(name = "registration", nullable = false, length = 12)
    private String registration;

    /** Empty allows every fuel. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_fuel_types", nullable = false)
    @Builder.Default
    private String[] allowedFuelTypes = {};

    @Column(name = "monthly_litre_limit")
    private BigDecimal monthlyLitreLimit;

    @Column(name = "monthly_amount_limit")
    private BigDecimal monthlyAmountLimit;

    @Column(name = "default_driver_name", length = 100)
    private String defaultDriverName;

    @Column(name = "default_driver_phone", length = 15)
    private String defaultDriverPhone;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean allowsFuel(String fuelType) {
        return allowedFuelTypes.length == 0 || fuelType == null || Arrays.asList(allowedFuelTypes).contains(fuelType);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
