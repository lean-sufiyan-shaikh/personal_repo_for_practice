package com.ppms.ancillary;

import com.ppms.transaction.TransactionPaymentMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RecordAncillarySaleRequest {

    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1 unit")
    private Integer quantityUnits;

    /**
     * Selling price per unit entered by the operator at point of sale.
     * This is the MRP (inclusive of all taxes) — GST is NOT added on top.
     */
    @NotNull(message = "Selling price per unit is required")
    @DecimalMin(value = "0.01", message = "Selling price must be greater than 0")
    private BigDecimal sellingPricePerUnit;

    @NotNull(message = "Payment mode is required")
    private TransactionPaymentMode paymentMode;

    /** Required when payment mode is CREDIT: the credit client of this pump the sale is charged to. */
    private Long clientId;

    /** Display name on receipts; defaults to the credit client's name for a CREDIT sale. */
    private String clientName;

    private String billNo;

    private String notes;

    /** The client's approved vehicle; preferred over vehicleRegistration when both are sent. */
    private Long vehicleId;

    /** A registration typed in when the vehicle is not picked from the client's list. */
    @Size(max = 30, message = "Vehicle registration must be at most 30 characters")
    private String vehicleRegistration;

    @Size(max = 100, message = "Driver name must be at most 100 characters")
    private String driverName;

    @Pattern(regexp = "\\d{10}", message = "Driver phone must be exactly 10 digits")
    private String driverPhone;
}
