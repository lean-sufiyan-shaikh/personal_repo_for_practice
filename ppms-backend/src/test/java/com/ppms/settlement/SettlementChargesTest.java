package com.ppms.settlement;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementChargesTest {

    private static final BigDecimal CARD_MDR = new BigDecimal("1.8");
    private static final BigDecimal GST = new BigDecimal("18");

    @Test
    void grossTypedTakesMdrOffTheGrossAndGstOffTheMdr() {
        SettlementCharges charges = SettlementCharges.fromRates(new BigDecimal("10000"), SettlementAmountBasis.GROSS, CARD_MDR, GST);

        assertThat(charges.grossAmount()).isEqualByComparingTo("10000.00");
        assertThat(charges.mdrAmount()).isEqualByComparingTo("180.00");
        assertThat(charges.gstOnMdrAmount()).isEqualByComparingTo("32.40");
        assertThat(charges.chargesAmount()).isEqualByComparingTo("212.40");
        assertThat(charges.netAmount()).isEqualByComparingTo("9787.60");
    }

    @Test
    void netTypedGrossesUpSoTheNetStaysExactlyWhatReachedTheBank() {
        SettlementCharges charges = SettlementCharges.fromRates(new BigDecimal("9787.60"), SettlementAmountBasis.NET, CARD_MDR, GST);

        assertThat(charges.netAmount()).isEqualByComparingTo("9787.60");
        assertThat(charges.grossAmount()).isEqualByComparingTo("10000.00");
        assertThat(charges.mdrAmount()).isEqualByComparingTo("180.00");
        assertThat(charges.gstOnMdrAmount()).isEqualByComparingTo("32.40");
    }

    @Test
    void netTypedWithAnAwkwardAmountStillAddsUpToThePaisa() {
        SettlementCharges charges = SettlementCharges.fromRates(new BigDecimal("1000"), SettlementAmountBasis.NET, CARD_MDR, GST);

        assertThat(charges.netAmount()).isEqualByComparingTo("1000.00");
        assertThat(charges.grossAmount()).isEqualByComparingTo("1021.70");
        assertThat(charges.mdrAmount()).isEqualByComparingTo("18.39");
        assertThat(charges.gstOnMdrAmount()).isEqualByComparingTo("3.31");
        assertThat(charges.grossAmount().subtract(charges.chargesAmount())).isEqualByComparingTo(charges.netAmount());
    }

    @Test
    void zeroMdrLeavesGrossAndNetEqual() {
        SettlementCharges charges = SettlementCharges.fromRates(new BigDecimal("2500.50"), SettlementAmountBasis.NET,
                BigDecimal.ZERO, GST);

        assertThat(charges.grossAmount()).isEqualByComparingTo("2500.50");
        assertThat(charges.chargesAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void typedChargesOnANetAmountAreAddedBackToGiveTheGross() {
        SettlementCharges charges = SettlementCharges.fromAmounts(new BigDecimal("9700"), SettlementAmountBasis.NET,
                new BigDecimal("250"), new BigDecimal("45"));

        assertThat(charges.grossAmount()).isEqualByComparingTo("9995.00");
        assertThat(charges.netAmount()).isEqualByComparingTo("9700.00");
    }

    @Test
    void typedChargesOnAGrossAmountAreTakenOffItToGiveTheNet() {
        SettlementCharges charges = SettlementCharges.fromAmounts(new BigDecimal("10000"), SettlementAmountBasis.GROSS,
                new BigDecimal("250"), new BigDecimal("45"));

        assertThat(charges.netAmount()).isEqualByComparingTo("9705.00");
    }

    @Test
    void gstOnATypedMdrUsesTheConfiguredPercent() {
        assertThat(SettlementCharges.gstOn(new BigDecimal("180"), GST)).isEqualByComparingTo("32.40");
    }

    @Test
    void chargesAsLargeAsTheGrossAreRefused() {
        assertThatThrownBy(() -> SettlementCharges.fromAmounts(new BigDecimal("100"), SettlementAmountBasis.GROSS,
                new BigDecimal("90"), new BigDecimal("10")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.SETTLEMENT_CHARGES_EXCEED_AMOUNT);
    }

    @Test
    void aZeroAmountIsRefused() {
        assertThatThrownBy(() -> SettlementCharges.fromRates(BigDecimal.ZERO, SettlementAmountBasis.GROSS, CARD_MDR, GST))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.SETTLEMENT_AMOUNT_NOT_POSITIVE);
    }

    @Test
    void negativeTypedChargesAreRefused() {
        assertThatThrownBy(() -> SettlementCharges.fromAmounts(new BigDecimal("100"), SettlementAmountBasis.GROSS,
                new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.SETTLEMENT_CHARGES_NEGATIVE);
    }
}
