package com.ppms.collection;

import java.math.BigDecimal;
import java.util.List;

/**
 * The collections view of one pump: what is overdue and how old, what came in this month, promises falling due or
 * missed, accounts on hold, the largest overdue accounts and today's reminders.
 *
 * @param missedPromises promises missed in the last 30 days, newest first, at most 20
 * @param topOverdue     the five overdue accounts owing the most
 */
public record CollectionDashboardResponse(BigDecimal totalOutstanding, BigDecimal totalOverdue, int overdueClientCount,
                                          AgeBuckets ageBuckets, BigDecimal collectedThisMonth,
                                          List<PromiseResponse> promisesDueToday, List<PromiseResponse> missedPromises,
                                          List<AccountFigure> clientsOnHold, List<AccountFigure> topOverdue,
                                          List<ReminderDueResponse> toRemind) {

    public record AgeBuckets(BigDecimal upTo30Days, BigDecimal days31To60, BigDecimal days61To90,
                             BigDecimal over90Days) {}

    public record AccountFigure(Long clientId, String name, BigDecimal outstanding, long daysOverdue) {}
}
