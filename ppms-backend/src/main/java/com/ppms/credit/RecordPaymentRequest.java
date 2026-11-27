package com.ppms.credit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RecordPaymentRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    private BigDecimal amount;

    @NotNull(message = "Payment mode is required")
    private PaymentMode paymentMode;

    @NotNull(message = "Payment date is required")
    @PastOrPresent(message = "Payment date cannot be in the future")
    private LocalDate paidAt;

    /** Cheque number, NEFT/IMPS ref or UPI transaction ID; required for UPI, BANK_TRANSFER and CHEQUE. */
    @Size(max = 100, message = "Reference must be at most 100 characters")
    private String referenceNo;

    @Size(max = 100, message = "Bank name must be at most 100 characters")
    private String chequeBank;

    /** A cheque dated after paidAt is post-dated and waits for approval until this date. */
    private LocalDate chequeDate;

    /** True once the user has seen the possible-duplicate warning for this reference and still wants to record it. */
    private boolean confirmDuplicate;

    /** True when the user agreed to keep what is paid above the balance as an advance for future bills. */
    private boolean recordAsAdvance;

    // Optional note, e.g. "Partial payment for March outstanding"
    private String notes;
}
