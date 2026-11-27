package com.ppms.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The problems found on one imported row while it is checked, and whether it is skipped as already present. */
final class RowCheck {

    private final int rowNumber;
    private final List<ImportIssue> errors = new ArrayList<>();
    private ImportIssue skipNote;

    RowCheck(int rowNumber) {
        this.rowNumber = rowNumber;
    }

    int rowNumber() {
        return rowNumber;
    }

    void error(String column, String code) {
        error(column, code, Map.of());
    }

    void error(String column, String code, Map<String, Object> params) {
        errors.add(new ImportIssue(column, code, params));
    }

    void skip(String column, String code, Map<String, Object> params) {
        skipNote = new ImportIssue(column, code, params);
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    boolean isSkipped() {
        return !hasErrors() && skipNote != null;
    }

    boolean isCreated() {
        return !hasErrors() && skipNote == null;
    }

    ImportRowResult toResult() {
        if (hasErrors()) {
            return new ImportRowResult(rowNumber, RowOutcome.ERROR, List.copyOf(errors));
        }
        return skipNote != null
                ? new ImportRowResult(rowNumber, RowOutcome.SKIP, List.of(skipNote))
                : new ImportRowResult(rowNumber, RowOutcome.CREATE, List.of());
    }
}
