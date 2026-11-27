package com.ppms.bank;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A record a statement line could be matched to, with the amount that should appear on the statement
 * (a settlement's net, a deposit's or payment's amount) and the reference the bank may show.
 */
public record MatchCandidate(BankMatchRecordType type, Long id, LocalDate date, BigDecimal amount,
                             String reference, String description) {

    public BankMatchRef ref() {
        return new BankMatchRef(type, id);
    }
}
