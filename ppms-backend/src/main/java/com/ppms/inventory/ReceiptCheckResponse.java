package com.ppms.inventory;

import java.math.BigDecimal;

/**
 * @param varianceLitres    received minus invoiced litres; negative when short
 * @param variancePercent   the variance as a signed share of the invoiced litres
 * @param densityDifference observed minus invoice density, or null unless both were entered
 */
public record ReceiptCheckResponse(
        BigDecimal dipBeforeLitres,
        BigDecimal dipAfterLitres,
        BigDecimal soldBetweenLitres,
        BigDecimal receivedLitres,
        BigDecimal varianceLitres,
        BigDecimal variancePercent,
        BigDecimal tolerancePercent,
        BigDecimal observedDensity,
        BigDecimal invoiceDensity,
        BigDecimal densityDifference,
        BigDecimal densityTolerance
) {

    /** Null for a delivery recorded before receipt checks existed. */
    static ReceiptCheckResponse of(TankerDelivery delivery) {
        if (delivery.getReceivedLitres() == null) {
            return null;
        }
        return new ReceiptCheckResponse(delivery.getDipBeforeLitres(), delivery.getDipAfterLitres(),
                delivery.getSoldBetweenLitres(), delivery.getReceivedLitres(), delivery.getVarianceLitres(),
                ReceiptCheckCalculator.percentOfInvoice(delivery.getVarianceLitres(), delivery.getQuantityDelivered()),
                delivery.getReceiptTolerancePercent(), delivery.getObservedDensity(), delivery.getInvoiceDensity(),
                ReceiptCheckCalculator.densityDifference(delivery.getObservedDensity(), delivery.getInvoiceDensity()),
                delivery.getDensityTolerance());
    }
}
