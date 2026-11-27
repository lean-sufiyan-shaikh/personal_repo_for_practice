package com.ppms.onboarding;

import com.ppms.common.exception.ErrorCodes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the text of one spreadsheet cell as a typed value. A value that cannot be read is reported on the row's
 * {@link RowCheck} against its column, and null is returned, so every problem in a row is found in one pass.
 */
final class CellReader {

    private static final Set<String> YES = Set.of("YES", "Y", "TRUE", "1");
    private static final Set<String> NO = Set.of("NO", "N", "FALSE", "0");

    private CellReader() {
    }

    static String text(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    static String requiredText(RowCheck row, String column, String raw, int maxLength) {
        String value = text(raw);
        if (value == null) {
            row.error(column, ErrorCodes.IMPORT_VALUE_REQUIRED);
            return null;
        }
        return withinLength(row, column, value, maxLength);
    }

    static String optionalText(RowCheck row, String column, String raw, int maxLength) {
        String value = text(raw);
        return value == null ? null : withinLength(row, column, value, maxLength);
    }

    /** A name shown on screens and invoices; angle brackets are refused as the client form refuses them. */
    static String requiredName(RowCheck row, String column, String raw, int maxLength) {
        String value = requiredText(row, column, raw, maxLength);
        if (value != null && (value.contains("<") || value.contains(">"))) {
            row.error(column, ErrorCodes.IMPORT_TEXT_HAS_ANGLE_BRACKETS);
            return null;
        }
        return value;
    }

    /** Ten digits; spaces, dashes and a leading +91 written in the sheet are dropped. */
    static String requiredPhone(RowCheck row, String column, String raw) {
        String value = text(raw);
        if (value == null) {
            row.error(column, ErrorCodes.IMPORT_VALUE_REQUIRED);
            return null;
        }
        String digits = value.replaceAll("[\\s-]", "");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        if (!digits.matches("\\d{10}")) {
            row.error(column, ErrorCodes.IMPORT_PHONE_INVALID);
            return null;
        }
        return digits;
    }

    static BigDecimal decimal(RowCheck row, String column, String raw, boolean required,
                              BigDecimal min, BigDecimal max, int maxDecimals) {
        String value = text(raw);
        if (value == null) {
            if (required) {
                row.error(column, ErrorCodes.IMPORT_VALUE_REQUIRED);
            }
            return null;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            row.error(column, ErrorCodes.IMPORT_NUMBER_INVALID);
            return null;
        }
        if (number.stripTrailingZeros().scale() > maxDecimals) {
            row.error(column, ErrorCodes.IMPORT_TOO_MANY_DECIMALS, Map.of("decimals", maxDecimals));
            return null;
        }
        if (min != null && number.compareTo(min) < 0) {
            row.error(column, ErrorCodes.IMPORT_NUMBER_TOO_SMALL, Map.of("min", min.toPlainString()));
            return null;
        }
        if (max != null && number.compareTo(max) > 0) {
            row.error(column, ErrorCodes.IMPORT_NUMBER_TOO_LARGE, Map.of("max", max.stripTrailingZeros().toPlainString()));
            return null;
        }
        return number;
    }

    static Integer wholeNumber(RowCheck row, String column, String raw, boolean required, int min, int max) {
        BigDecimal number = decimal(row, column, raw, required, BigDecimal.valueOf(min), BigDecimal.valueOf(max), 3);
        if (number == null) {
            return null;
        }
        if (number.stripTrailingZeros().scale() > 0) {
            row.error(column, ErrorCodes.IMPORT_WHOLE_NUMBER_REQUIRED);
            return null;
        }
        return number.intValueExact();
    }

    /** A date written as YYYY-MM-DD, as the import screen sends every date cell; it cannot be after {@code latest}. */
    static LocalDate date(RowCheck row, String column, String raw, LocalDate latest) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            row.error(column, ErrorCodes.IMPORT_DATE_INVALID);
            return null;
        }
        if (date.isAfter(latest)) {
            row.error(column, ErrorCodes.IMPORT_DATE_IN_FUTURE);
            return null;
        }
        return date;
    }

    static <E extends Enum<E>> E choice(RowCheck row, String column, String raw, boolean required, Collection<E> allowed) {
        String value = text(raw);
        if (value == null) {
            if (required) {
                row.error(column, ErrorCodes.IMPORT_VALUE_REQUIRED);
            }
            return null;
        }
        String wanted = value.toUpperCase(Locale.ROOT).replace(' ', '_');
        Optional<E> match = allowed.stream().filter(option -> option.name().equals(wanted)).findFirst();
        if (match.isEmpty()) {
            row.error(column, ErrorCodes.IMPORT_CHOICE_INVALID, Map.of("allowed",
                    allowed.stream().map(Enum::name).collect(Collectors.joining(", "))));
            return null;
        }
        return match.get();
    }

    static String textChoice(RowCheck row, String column, String raw, String... allowed) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        String wanted = value.toLowerCase(Locale.ROOT);
        if (Arrays.stream(allowed).noneMatch(wanted::equals)) {
            row.error(column, ErrorCodes.IMPORT_CHOICE_INVALID, Map.of("allowed", String.join(", ", allowed)));
            return null;
        }
        return wanted;
    }

    static boolean yesNo(RowCheck row, String column, String raw) {
        String value = text(raw);
        if (value == null) {
            return false;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (YES.contains(upper)) {
            return true;
        }
        if (!NO.contains(upper)) {
            row.error(column, ErrorCodes.IMPORT_YES_NO_INVALID);
        }
        return false;
    }

    private static String withinLength(RowCheck row, String column, String value, int maxLength) {
        if (value.length() > maxLength) {
            row.error(column, ErrorCodes.IMPORT_VALUE_TOO_LONG, Map.of("maxLength", maxLength));
            return null;
        }
        return value;
    }

    static String lowerCaseKey(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
