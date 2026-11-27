package com.ppms.settlement;

/** Which amount a person typed when recording a settlement. */
public enum SettlementAmountBasis {
    /** The amount cleared against collections, before the bank's charges. */
    GROSS,
    /** The amount that actually reached the bank account, after the bank's charges. */
    NET
}
