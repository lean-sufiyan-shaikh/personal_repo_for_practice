package com.ppms.collection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record MonthlyStatementResponse(Long id, Long clientId, String clientName, LocalDate periodFrom,
                                       LocalDate periodTo, BigDecimal closingBalance, Integer transactionCount,
                                       OffsetDateTime generatedAt) {
}
