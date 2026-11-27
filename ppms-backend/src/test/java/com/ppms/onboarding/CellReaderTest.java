package com.ppms.onboarding;

import com.ppms.auth.PasswordPolicy;
import com.ppms.user.UserGender;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CellReaderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void phoneKeepsTenDigitsAndDropsSpacingAndCountryCode() {
        RowCheck row = new RowCheck(2);

        assertThat(CellReader.requiredPhone(row, "phone", " 98765 43210 ")).isEqualTo("9876543210");
        assertThat(CellReader.requiredPhone(row, "phone", "+91-98765-43210")).isEqualTo("9876543210");
        assertThat(CellReader.requiredPhone(row, "phone", "919876543210")).isEqualTo("9876543210");
        assertThat(row.hasErrors()).isFalse();

        assertThat(CellReader.requiredPhone(row, "phone", "98765")).isNull();
        assertThat(issueCodes(row)).containsExactly("IMPORT_PHONE_INVALID");
    }

    @Test
    void decimalAcceptsThousandSeparatorsAndRefusesTooManyDecimalsOrOutOfRange() {
        RowCheck row = new RowCheck(2);

        assertThat(CellReader.decimal(row, "amount", "1,250.50", true, BigDecimal.ZERO, null, 2)).isEqualByComparingTo("1250.50");
        assertThat(CellReader.decimal(row, "amount", "1.505", true, BigDecimal.ZERO, null, 2)).isNull();
        assertThat(CellReader.decimal(row, "amount", "-1", true, BigDecimal.ZERO, null, 2)).isNull();
        assertThat(CellReader.decimal(row, "amount", "11", true, BigDecimal.ZERO, BigDecimal.TEN, 2)).isNull();
        assertThat(CellReader.decimal(row, "amount", "", true, BigDecimal.ZERO, null, 2)).isNull();
        assertThat(CellReader.decimal(row, "amount", "", false, BigDecimal.ZERO, null, 2)).isNull();

        assertThat(issueCodes(row)).containsExactly("IMPORT_TOO_MANY_DECIMALS", "IMPORT_NUMBER_TOO_SMALL",
                "IMPORT_NUMBER_TOO_LARGE", "IMPORT_VALUE_REQUIRED");
    }

    @Test
    void choiceIgnoresCaseAndListsTheAllowedValuesWhenWrong() {
        RowCheck row = new RowCheck(2);

        assertThat(CellReader.choice(row, "gender", "female", true, List.of(UserGender.values()))).isEqualTo(UserGender.FEMALE);
        assertThat(CellReader.choice(row, "gender", "unknown", true, List.of(UserGender.values()))).isNull();

        assertThat(row.toResult().issues().get(0).params()).containsEntry("allowed", "MALE, FEMALE, OTHER");
    }

    @Test
    void dateMustBeIsoAndNotAfterTheLatestAllowed() {
        RowCheck row = new RowCheck(2);

        assertThat(CellReader.date(row, "date", "2026-11-01", TODAY)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(CellReader.date(row, "date", "01/11/2026", TODAY)).isNull();
        assertThat(CellReader.date(row, "date", "2026-11-28", TODAY)).isNull();

        assertThat(issueCodes(row)).containsExactly("IMPORT_DATE_INVALID", "IMPORT_DATE_IN_FUTURE");
    }

    @Test
    void textLongerThanTheColumnAllowsIsRefused() {
        RowCheck row = new RowCheck(2);

        assertThat(CellReader.optionalText(row, "notes", "x".repeat(11), 10)).isNull();
        assertThat(row.toResult().issues().get(0).params()).containsEntry("maxLength", 10);
    }

    @Test
    void skippedRowWithAnErrorIsReportedAsAnError() {
        RowCheck row = new RowCheck(2);
        row.skip("phone", "IMPORT_ALREADY_EXISTS", Map.of());
        row.error("role", "IMPORT_CHOICE_INVALID");

        assertThat(row.toResult().outcome()).isEqualTo(RowOutcome.ERROR);
    }

    @RepeatedTest(20)
    void temporaryPasswordsMeetThePasswordPolicy() {
        assertThat(new TemporaryPasswordGenerator().generate()).matches(PasswordPolicy.REGEX);
    }

    private static List<String> issueCodes(RowCheck row) {
        return row.toResult().issues().stream().map(ImportIssue::code).toList();
    }
}
