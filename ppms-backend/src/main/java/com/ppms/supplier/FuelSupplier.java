package com.ppms.supplier;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Represents a fuel supplier for a pump.
 * Suppliers can be tagged on tanker_deliveries for traceability.
 * Soft-delete via the 'active' flag — inactive suppliers are hidden from UI
 * but retained for historical delivery records.
 */
@Entity
@Table(name = "fuel_suppliers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FuelSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "notes")
    private String notes;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** What the pump owed this supplier on openingBalanceDate, before any delivery recorded here; negative is an advance. */
    @Column(name = "opening_balance", nullable = false)
    @Builder.Default
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "opening_balance_date")
    private LocalDate openingBalanceDate;

    /** The supplier a tanker delivery takes when none is picked; at most one per pump. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultSupplier;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        active = true;
        createdAt = OffsetDateTime.now();
    }
}
