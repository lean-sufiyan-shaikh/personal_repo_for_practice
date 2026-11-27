package com.ppms.bank;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Value
@Builder(toBuilder = true)
public class ImportSummaryResponse {
    Long id;
    Long pumpId;
    String bankName;
    String accountNumber;
    LocalDate statementFromDate;
    LocalDate statementToDate;
    int totalLines;
    int matchedLines;
    int suggestedLines;
    int ignoredLines;
    int unmatchedLines;
    /** Credits and debits of lines still UNMATCHED or SUGGESTED. */
    BigDecimal unmatchedCreditAmount;
    BigDecimal unmatchedDebitAmount;
    Long importedByUserId;
    OffsetDateTime importedAt;
    /** Filled only on the import response: lines already on an earlier statement, and unreadable rows. */
    Integer skippedDuplicateLines;
    Integer skippedInvalidRows;
    /** Filled only on import and auto-match responses. */
    Integer autoMatchedLines;
    Integer autoSuggestedLines;
}
