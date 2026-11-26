package com.ppms.inventory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptCheckCalculatorTest {

    private static final BigDecimal INVOICE = new BigDecimal("12000");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.20");

    @Test
    void receivedLitresAddTheLitresSoldBetweenTheTwoDips() {
        BigDecimal received = ReceiptCheckCalculator.receivedLitres(
                new BigDecimal("2000"), new BigDecimal("13900"), new BigDecimal("76"));

        assertThat(received).isEqualByComparingTo("11976");
    }

    @Test
    void shortByExactlyTheToleranceIsWithinTolerance() {
        assertThat(ReceiptCheckCalculator.statusOf(INVOICE, new BigDecimal("11976"), TOLERANCE))
                .isEqualTo(DeliveryCheckStatus.OK);
    }

    @Test
    void shortByMoreThanTheToleranceIsShort() {
        assertThat(ReceiptCheckCalculator.statusOf(INVOICE, new BigDecimal("11975.999"), TOLERANCE))
                .isEqualTo(DeliveryCheckStatus.SHORT);
    }

    @Test
    void overByMoreThanTheToleranceIsOverAndOverByLessIsOk() {
        assertThat(ReceiptCheckCalculator.statusOf(INVOICE, new BigDecimal("12024.001"), TOLERANCE))
                .isEqualTo(DeliveryCheckStatus.OVER);
        assertThat(ReceiptCheckCalculator.statusOf(INVOICE, new BigDecimal("12024"), TOLERANCE))
                .isEqualTo(DeliveryCheckStatus.OK);
    }

    @Test
    void densitiesFurtherApartThanTheToleranceAreFlaggedEitherWay() {
        BigDecimal tolerance = new BigDecimal("3.0");

        assertThat(ReceiptCheckCalculator.isDensityFlagged(
                ReceiptCheckCalculator.densityDifference(new BigDecimal("741.9"), new BigDecimal("745.0")), tolerance)).isTrue();
        assertThat(ReceiptCheckCalculator.isDensityFlagged(
                ReceiptCheckCalculator.densityDifference(new BigDecimal("748.0"), new BigDecimal("745.0")), tolerance)).isFalse();
        assertThat(ReceiptCheckCalculator.isDensityFlagged(
                ReceiptCheckCalculator.densityDifference(null, new BigDecimal("745.0")), tolerance)).isFalse();
    }

    @Test
    void aShortDeliveryCarriesTheInvoiceValueOverTheLitresReceived() {
        BigDecimal cost = ReceiptCheckCalculator.costPerReceivedLitre(new BigDecimal("1080000"), new BigDecimal("11880"));

        assertThat(cost).isEqualByComparingTo("90.909");
    }
}
