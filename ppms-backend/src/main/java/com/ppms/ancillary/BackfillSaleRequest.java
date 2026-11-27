package com.ppms.ancillary;

import com.ppms.transaction.TransactionPaymentMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * Request body for retroactively recording a historical ancillary counter sale.
 * The selling price is NOT provided by the caller — it is resolved automatically
 * from the product's price history for the given sale date.
 *
 * Restrictions enforced at the service layer:
 * - saleDate must be strictly before today (IST).
 * - At least one active stock lot must have been delivered on or before saleDate.
 * - Total units across those historical lots must cover the requested quantity.
 * - Only ADMIN and OWNER roles are permitted.
 */
@Data
public class BackfillSaleRequest {

    @NotNull(message = "Product ID is required")
    private Long productId;

    /**
     * The historical date on which this sale occurred.
     * Must be strictly before today. Validated in the service (not via @Past)
     * to ensure IST-aware comparison.
     */
    @NotNull(message = "Sale date is required")
    private LocalDate saleDate;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1 unit")
    private Integer quantityUnits;

    @NotNull(message = "Payment mode is required")
    private TransactionPaymentMode paymentMode;

    /** Required when paymentMode is CREDIT: the credit client of this pump the sale is charged to. */
    private Long clientId;

    /** Display name on the sale; defaults to the credit client's name. */
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
