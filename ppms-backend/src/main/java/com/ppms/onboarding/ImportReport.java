package com.ppms.onboarding;

import java.util.List;

/**
 * The checked rows of one import file, with counts. {@code imported} is false for a preview. {@code credentials} holds
 * the temporary passwords of imported staff and is only filled on the response that created them.
 */
public record ImportReport(int totalRows, int createCount, int skipCount, int errorCount, boolean imported,
                           List<ImportRowResult> rows, List<StaffCredential> credentials) {

    static ImportReport of(List<RowCheck> checks, boolean imported, List<StaffCredential> credentials) {
        List<ImportRowResult> rows = checks.stream().map(RowCheck::toResult).toList();
        return new ImportReport(rows.size(),
                count(rows, RowOutcome.CREATE),
                count(rows, RowOutcome.SKIP),
                count(rows, RowOutcome.ERROR),
                imported, rows, credentials);
    }

    private static int count(List<ImportRowResult> rows, RowOutcome outcome) {
        return (int) rows.stream().filter(row -> row.outcome() == outcome).count();
    }
}
