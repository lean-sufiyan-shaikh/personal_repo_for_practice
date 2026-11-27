package com.ppms.settlement;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * The split of one settlement: the gross cleared from collections, the bank's MDR on it, GST on that MDR,
 * and the net that reached the account. net = gross − mdr − gstOnMdr always holds to the paisa.
 */
public record SettlementCharges(BigDecimal grossAmount, BigDecimal mdrAmount, BigDecimal gstOnMdrAmount,
                                BigDecimal netAmount) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public BigDecimal chargesAmount() {
        return mdrAmount.add(gstOnMdrAmount);
    }

    /**
     * Charges worked out from the MDR and GST percentages. Typed as NET, the gross is grossed up so that the
     * net stays exactly what reached the bank; rounding lands on the GST.
     */
    public static SettlementCharges fromRates(BigDecimal amount, SettlementAmountBasis basis,
                                              BigDecimal mdrPercent, BigDecimal gstOnMdrPercent) {
        BigDecimal typed = money(amount);
        if (basis == SettlementAmountBasis.GROSS) {
            BigDecimal mdr = money(typed.multiply(mdrPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
            BigDecimal gst = money(mdr.multiply(gstOnMdrPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
            return checked(typed, mdr, gst);
        }
        BigDecimal mdrRate = mdrPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP);
        BigDecimal chargeRate = mdrRate.multiply(BigDecimal.ONE.add(gstOnMdrPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        if (chargeRate.compareTo(BigDecimal.ONE) >= 0) {
            throw chargesTooHigh(typed);
        }
        BigDecimal gross = money(typed.divide(BigDecimal.ONE.subtract(chargeRate), 10, RoundingMode.HALF_UP));
        BigDecimal mdr = money(gross.multiply(mdrRate));
        BigDecimal gst = gross.subtract(typed).subtract(mdr).max(BigDecimal.ZERO);
        return checked(typed.add(mdr).add(gst), mdr, gst);
    }

    /** Charges typed by the person: the gross or net follows from the typed amount and the charges. */
    public static SettlementCharges fromAmounts(BigDecimal amount, SettlementAmountBasis basis,
                                                BigDecimal mdrAmount, BigDecimal gstOnMdrAmount) {
        BigDecimal typed = money(amount);
        BigDecimal mdr = money(mdrAmount);
        BigDecimal gst = money(gstOnMdrAmount);
        if (mdr.signum() < 0 || gst.signum() < 0) {
            throw new BusinessException(ErrorCodes.SETTLEMENT_CHARGES_NEGATIVE, Map.of(),
                    "MDR and GST on MDR cannot be negative.");
        }
        BigDecimal gross = basis == SettlementAmountBasis.GROSS ? typed : typed.add(mdr).add(gst);
        return checked(gross, mdr, gst);
    }

    /** GST on a typed MDR at the configured GST percentage. */
    public static BigDecimal gstOn(BigDecimal mdrAmount, BigDecimal gstOnMdrPercent) {
        return money(money(mdrAmount).multiply(gstOnMdrPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }

    private static SettlementCharges checked(BigDecimal gross, BigDecimal mdr, BigDecimal gst) {
        BigDecimal net = gross.subtract(mdr).subtract(gst);
        if (gross.signum() <= 0) {
            throw new BusinessException(ErrorCodes.SETTLEMENT_AMOUNT_NOT_POSITIVE, Map.of(),
                    "A settlement amount must be more than zero.");
        }
        if (net.signum() <= 0) {
            throw chargesTooHigh(gross);
        }
        return new SettlementCharges(gross, mdr, gst, net);
    }

    private static BusinessException chargesTooHigh(BigDecimal amount) {
        return new BusinessException(ErrorCodes.SETTLEMENT_CHARGES_EXCEED_AMOUNT,
                Map.of("amount", amount.toPlainString()),
                "The bank charges are as large as the settlement of ₹" + amount + ". Check the MDR and GST amounts.");
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
