package com.ppms.payroll;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** paidDate and paymentMode are required, and only taken, when marking the payroll PAID. */
public record UpdatePayrollStatusRequest(
        String status,
        LocalDate paidDate,
        PayrollPaymentMode paymentMode,
        @Size(max = 100, message = "Reference must be at most 100 characters")
        String paymentReference
) {}
