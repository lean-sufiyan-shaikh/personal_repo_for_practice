package com.ppms.ancillary;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Represents a physical stock-in event — supplier delivers a batch of units.
 * Each delivery creates one AncillaryStockLot for FIFO cost tracking.
 */
@Entity
@Table(name = "ancillary_stock_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AncillaryStockDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    /** Whole units delivered — no fractional units allowed. */
    @Column(name = "quantity_units", nullable = false)
    private Integer quantityUnits;

    /** Cost price paid per unit to the supplier. Used for COGS calculation. */
    @Column(name = "cost_price_per_unit", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPricePerUnit;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(name = "invoice_reference", length = 100)
    private String invoiceReference;

    @Column(name = "notes")
    private String notes;

    /** GST input from the supplier's bill; every gst field is null when the purchase carries no GST details. */
    @Column(name = "gst_supplier_name", length = 150)
    private String gstSupplierName;

    @Column(name = "gst_supplier_gstin", length = 15)
    private String gstSupplierGstin;

    @Column(name = "gst_invoice_date")
    private LocalDate gstInvoiceDate;

    @Column(name = "gst_taxable_value", precision = 14, scale = 2)
    private BigDecimal gstTaxableValue;

    @Column(name = "gst_rate_percent", precision = 5, scale = 2)
    private BigDecimal gstRatePercent;

    @Column(name = "cgst_amount", precision = 12, scale = 2)
    private BigDecimal cgstAmount;

    @Column(name = "sgst_amount", precision = 12, scale = 2)
    private BigDecimal sgstAmount;

    @Column(name = "igst_amount", precision = 12, scale = 2)
    private BigDecimal igstAmount;

    @Column(name = "logged_by_user_id", nullable = false)
    private Long loggedByUserId;

    /**
     * True when this delivery was entered retroactively for a past date by Admin/Owner.
     * Delivery date < today at record time triggers this flag automatically.
     */
    @JsonProperty("isBackfilled")
    @Column(name = "is_backfilled", nullable = false)
    @Builder.Default
    private boolean isBackfilled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
