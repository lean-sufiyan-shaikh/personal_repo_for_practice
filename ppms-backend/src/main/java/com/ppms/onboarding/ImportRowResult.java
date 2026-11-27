package com.ppms.onboarding;

import java.util.List;

/** One spreadsheet row: its number as the owner sees it in Excel, what importing it does, and why. */
public record ImportRowResult(int rowNumber, RowOutcome outcome, List<ImportIssue> issues) {
}
