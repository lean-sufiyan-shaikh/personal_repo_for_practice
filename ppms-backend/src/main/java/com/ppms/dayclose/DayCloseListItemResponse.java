package com.ppms.dayclose;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A closed day with the flags of its current summary, for the owner's list. */
public record DayCloseListItemResponse(LocalDate businessDate, DayCloseStatus status, int version, String closedByName,
                                       OffsetDateTime closedAt, String approvedByName, OffsetDateTime approvedAt,
                                       FlagColour overallFlag, FlagColour salesFlag, FlagColour cashFlag,
                                       FlagColour stockFlag, FlagColour creditFlag, BigDecimal fuelRevenue,
                                       BigDecimal grossMargin, long commentCount) {
}
