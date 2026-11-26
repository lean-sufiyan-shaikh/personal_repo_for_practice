package com.ppms.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Received litres = dip after − dip before + litres the tank's nozzles sold in between. A difference from the
 * invoice of up to the tolerance share of the invoice litres, either way, is normal measuring error.
 */
final class ReceiptCheckCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int LITRES_SCALE = 3;

    private ReceiptCheckCalculator() {
    }

    static BigDecimal receivedLitres(BigDecimal dipBefore, BigDecimal dipAfter, BigDecimal soldBetween) {
        return dipAfter.subtract(dipBefore).add(soldBetween).setScale(LITRES_SCALE, RoundingMode.HALF_UP);
    }

    static DeliveryCheckStatus statusOf(BigDecimal invoiceLitres, BigDecimal receivedLitres, BigDecimal tolerancePercent) {
        BigDecimal allowed = invoiceLitres.multiply(tolerancePercent).divide(HUNDRED, LITRES_SCALE, RoundingMode.HALF_UP);
        BigDecimal variance = receivedLitres.subtract(invoiceLitres);
        if (variance.negate().compareTo(allowed) > 0) {
            return DeliveryCheckStatus.SHORT;
        }
        if (variance.compareTo(allowed) > 0) {
            return DeliveryCheckStatus.OVER;
        }
        return DeliveryCheckStatus.OK;
    }

    /** Null unless both densities were entered. */
    static BigDecimal densityDifference(BigDecimal observedDensity, BigDecimal invoiceDensity) {
        if (observedDensity == null || invoiceDensity == null) {
            return null;
        }
        return observedDensity.subtract(invoiceDensity).setScale(1, RoundingMode.HALF_UP);
    }

    static boolean isDensityFlagged(BigDecimal densityDifference, BigDecimal densityTolerance) {
        return densityDifference != null && densityDifference.abs().compareTo(densityTolerance) > 0;
    }

    static BigDecimal percentOfInvoice(BigDecimal litres, BigDecimal invoiceLitres) {
        return litres.multiply(HUNDRED).divide(invoiceLitres, 2, RoundingMode.HALF_UP);
    }

    /** The invoice value spread over the litres really received, so a short delivery costs more per litre. */
    static BigDecimal costPerReceivedLitre(BigDecimal invoiceValue, BigDecimal receivedLitres) {
        return invoiceValue.divide(receivedLitres, 3, RoundingMode.HALF_UP);
    }
}
