package com.ppms.bank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BankStatementCsvTest {

    @Test
    void quotedFieldKeepsItsComma() {
        assertThat(BankStatementCsv.splitLine("01/05/2026,\"UPI, ref 9\",,\"1,234.56\",\"10,000.00\",UTR1"))
                .contains(List.of("01/05/2026", "UPI, ref 9", "", "1,234.56", "10,000.00", "UTR1"));
    }

    @Test
    void doubledQuoteInsideQuotedFieldIsOneQuote() {
        assertThat(BankStatementCsv.splitLine("\"He said \"\"paid\"\"\",1"))
                .contains(List.of("He said \"paid\"", "1"));
    }

    @Test
    void unclosedQuoteMakesTheLineUnreadable() {
        assertThat(BankStatementCsv.splitLine("01/05/2026,\"open,500")).isEmpty();
    }

    @Test
    void amountWithCurrencyAndThousandsSeparatorIsParsed() {
        assertThat(BankStatementCsv.parseMoney("₹1,234.56")).contains(new BigDecimal("1234.56"));
        assertThat(BankStatementCsv.parseMoney("Rs. 1,234.56")).contains(new BigDecimal("1234.56"));
    }

    @Test
    void minusSignAndBracketsMakeTheAmountNegative() {
        assertThat(BankStatementCsv.parseMoney("-500")).contains(new BigDecimal("-500"));
        assertThat(BankStatementCsv.parseMoney("(500.00)")).contains(new BigDecimal("-500.00"));
    }

    @Test
    void blankCellIsZero() {
        assertThat(BankStatementCsv.parseMoney("  ")).contains(BigDecimal.ZERO);
    }

    @Test
    void textThatIsNotANumberIsRejectedInsteadOfBecomingZero() {
        assertThat(BankStatementCsv.parseMoney("N/A")).isEqualTo(Optional.empty());
        assertThat(BankStatementCsv.parseMoney("12.3.4")).isEqualTo(Optional.empty());
    }
}
