package com.ppms.bank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parsing helpers for bank statement CSV rows (RFC 4180 quoting on a single line).
 */
final class BankStatementCsv {

    private BankStatementCsv() {
    }

    /**
     * Splits one CSV line into fields, honouring double-quoted fields that contain commas
     * and escaped quotes (""). Returns empty when a quoted field is never closed.
     */
    static Optional<List<String>> splitLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            return Optional.empty();
        }
        fields.add(field.toString());
        return Optional.of(fields);
    }

    /**
     * Parses an amount such as "₹1,234.56", "Rs. 1,234.56", "-500" or "(500.00)". A blank cell is zero.
     * A leading minus or surrounding brackets make the amount negative.
     * Returns empty when the cell is not a number, so the caller can skip the row.
     */
    static Optional<BigDecimal> parseMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(BigDecimal.ZERO);
        }
        String value = raw.trim();
        boolean negative = false;
        if (value.startsWith("(") && value.endsWith(")")) {
            negative = true;
            value = value.substring(1, value.length() - 1).trim();
        }
        value = value.replaceAll("[₹$,\\s]|(?i)rs\\.?|(?i)inr", "");
        if (value.startsWith("-")) {
            negative = !negative;
            value = value.substring(1);
        }
        if (!value.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
            return Optional.empty();
        }
        BigDecimal amount = new BigDecimal(value);
        return Optional.of(negative ? amount.negate() : amount);
    }
}
