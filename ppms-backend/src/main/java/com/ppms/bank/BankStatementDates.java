package com.ppms.bank;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads statement dates written as 2026-05-04, 04/05/2026 or 04-05-2026. */
final class BankStatementDates {

    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})");

    private BankStatementDates() {
    }

    /** The requested order, or for AUTO the one the file's dates show; day first when nothing tells them apart. */
    static BankStatementDateFormat resolve(Collection<String> rawDates, BankStatementDateFormat requested) {
        if (requested != BankStatementDateFormat.AUTO) {
            return requested;
        }
        boolean dayFirstSeen = false;
        boolean monthFirstSeen = false;
        for (String raw : rawDates) {
            Matcher matcher = DAY_MONTH_YEAR.matcher(raw.trim());
            if (!matcher.matches()) {
                continue;
            }
            dayFirstSeen |= Integer.parseInt(matcher.group(1)) > 12;
            monthFirstSeen |= Integer.parseInt(matcher.group(2)) > 12;
        }
        if (dayFirstSeen && monthFirstSeen) {
            throw new BusinessException(ErrorCodes.BANK_STATEMENT_DATE_FORMAT_AMBIGUOUS, Map.of(),
                    "The statement's dates mix day-first and month-first order. Choose the date format and import again.");
        }
        return monthFirstSeen ? BankStatementDateFormat.MONTH_FIRST : BankStatementDateFormat.DAY_FIRST;
    }

    /** The date, or null when the text is not a valid date in the given order. */
    static LocalDate parse(String raw, BankStatementDateFormat format) {
        String text = raw.trim();
        try {
            Matcher iso = ISO.matcher(text);
            if (iso.matches()) {
                return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            }
            Matcher matcher = DAY_MONTH_YEAR.matcher(text);
            if (!matcher.matches()) {
                return null;
            }
            int first = Integer.parseInt(matcher.group(1));
            int second = Integer.parseInt(matcher.group(2));
            int year = Integer.parseInt(matcher.group(3));
            return format == BankStatementDateFormat.MONTH_FIRST
                    ? LocalDate.of(year, first, second)
                    : LocalDate.of(year, second, first);
        } catch (DateTimeException ex) {
            return null;
        }
    }
}
