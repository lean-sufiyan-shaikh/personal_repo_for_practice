package com.ppms.ancillary;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.invoice.GstFields;
import com.ppms.invoice.GstTaxSplit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Input GST of a counter-product purchase: CGST + SGST when the supplier is registered in the pump's state, IGST when
 * it is in another state, read from the first two digits of each GSTIN.
 */
public record PurchaseGst(String supplierName, String supplierGstin, LocalDate invoiceDate, BigDecimal taxableValue,
                          BigDecimal gstRatePercent, GstTaxSplit split) {

    private static final Set<BigDecimal> RATES = Set.of(new BigDecimal("0.00"), new BigDecimal("5.00"),
            new BigDecimal("12.00"), new BigDecimal("18.00"), new BigDecimal("28.00"));
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public static PurchaseGst of(PurchaseGstRequest request, String pumpGstin, String invoiceNumber, LocalDate today) {
        if (pumpGstin == null) {
            throw new BusinessException(ErrorCodes.GST_INPUT_PUMP_GSTIN_MISSING, Map.of(),
                    "Add the pump's GSTIN in settings before recording GST on a purchase.");
        }
        if (invoiceNumber == null) {
            throw new BusinessException(ErrorCodes.GST_INPUT_INVOICE_NUMBER_REQUIRED, Map.of(),
                    "The supplier's invoice number is required to record GST on a purchase.");
        }
        if (request.invoiceDate().isAfter(today)) {
            throw new BusinessException(ErrorCodes.GST_INPUT_INVOICE_DATE_IN_FUTURE, Map.of(),
                    "The purchase invoice date cannot be in the future.");
        }
        BigDecimal rate = request.gstRatePercent().setScale(2, RoundingMode.HALF_UP);
        if (!RATES.contains(rate)) {
            throw new BusinessException(ErrorCodes.GST_INPUT_RATE_INVALID, Map.of("gstRate", rate.toPlainString()),
                    "GST rate must be 0, 5, 12, 18 or 28 percent.");
        }
        String supplierGstin = GstFields.normaliseGstin(request.supplierGstin());
        BigDecimal taxableValue = request.taxableValue().setScale(2, RoundingMode.HALF_UP);
        BigDecimal gst = taxableValue.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        boolean sameState = GstFields.stateCodeOf(supplierGstin).equals(GstFields.stateCodeOf(pumpGstin));
        return new PurchaseGst(request.supplierName().trim(), supplierGstin, request.invoiceDate(), taxableValue, rate,
                GstTaxSplit.of(gst, sameState));
    }

    public BigDecimal totalGst() {
        return split.cgst().add(split.sgst()).add(split.igst());
    }

    void applyTo(AncillaryStockDelivery delivery) {
        delivery.setGstSupplierName(supplierName);
        delivery.setGstSupplierGstin(supplierGstin);
        delivery.setGstInvoiceDate(invoiceDate);
        delivery.setGstTaxableValue(taxableValue);
        delivery.setGstRatePercent(gstRatePercent);
        delivery.setCgstAmount(split.cgst());
        delivery.setSgstAmount(split.sgst());
        delivery.setIgstAmount(split.igst());
    }
}
