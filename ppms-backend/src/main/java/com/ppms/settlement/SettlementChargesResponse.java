package com.ppms.settlement;

import java.math.BigDecimal;

/** Charges a settlement would carry, worked out the same way recording it would. */
public record SettlementChargesResponse(BigDecimal grossAmount, BigDecimal mdrAmount, BigDecimal gstOnMdrAmount,
                                        BigDecimal chargesAmount, BigDecimal netAmount,
                                        BigDecimal mdrPercent, BigDecimal gstOnMdrPercent) {
}
