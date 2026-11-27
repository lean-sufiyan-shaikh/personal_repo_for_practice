package com.ppms.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A month's GST in GSTR-3B style buckets, for the pump's accountant to verify before filing. Net tax is output less
 * input per head; a negative figure is credit carried forward.
 */
public record GstSummaryResponse(
        String month,
        LocalDate from,
        LocalDate to,
        String pumpGstin,
        TaxBucket outwardTaxable,
        TaxBucket outwardFromInvoices,
        TaxBucket outwardFromCounterSales,
        BigDecimal nonGstOutwardFuel,
        TaxBucket inputTax,
        TaxBucket netTax,
        int counterSalesWithoutGst,
        List<PurchaseLine> purchases
) {

    public record TaxBucket(BigDecimal taxableValue, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {

        static final TaxBucket NONE = new TaxBucket(GstSummaryService.ZERO, GstSummaryService.ZERO,
                GstSummaryService.ZERO, GstSummaryService.ZERO);

        public BigDecimal totalTax() {
            return cgst.add(sgst).add(igst);
        }

        TaxBucket plus(TaxBucket other) {
            return new TaxBucket(taxableValue.add(other.taxableValue), cgst.add(other.cgst), sgst.add(other.sgst),
                    igst.add(other.igst));
        }

        TaxBucket minusTax(TaxBucket other) {
            return new TaxBucket(taxableValue, cgst.subtract(other.cgst), sgst.subtract(other.sgst),
                    igst.subtract(other.igst));
        }
    }

    /** One line of the GST input register: a counter-product purchase bill with GST. */
    public record PurchaseLine(Long deliveryId, LocalDate invoiceDate, String invoiceNumber, String supplierName,
                               String supplierGstin, String productName, BigDecimal taxableValue, BigDecimal gstRatePercent,
                               BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total) {
    }
}
