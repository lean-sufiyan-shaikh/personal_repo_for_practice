package com.ppms.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * How old each credit account's unpaid balance is. One row per standalone client or parent account; a parent's
 * figures cover its whole group, the way limits and billing cycles are checked.
 */
public record CreditAgingResponse(Summary summary, List<Row> clients) {

    /**
     * @param overdueAmount   balance of the accounts whose billing cycle is overdue
     * @param overSixtyAmount part of the balance from sales and interest more than 60 days old
     */
    public record Summary(BigDecimal totalOutstanding, BigDecimal overdueAmount, BigDecimal overSixtyAmount,
                          int overdueCount, int overLimitCount, int pendingApprovalCount) {}

    /**
     * @param oldestUnpaidSaleDate earliest sale still carrying part of the balance, payments covering the oldest first
     * @param daysOverdue          days the oldest unpaid bill is past its due date (one billing cycle after it);
     *                             0 when no unpaid bill is past due
     * @param overdueSince         first day of the unbroken spell in which some bill has been unpaid past its due
     *                             date; null when not overdue. Collections keys its reminder episodes on it.
     * @param creditStatus         whether the account can take a credit sale now, as the client picker shows it
     */
    public record Row(Long clientId, String name, String phone, int subAccountCount, BillingCycle billingCycle,
                      BigDecimal creditLimit, BigDecimal outstanding,
                      BigDecimal upTo30Days, BigDecimal days31To60, BigDecimal days61To90, BigDecimal over90Days,
                      LocalDate oldestUnpaidSaleDate, LocalDate lastPaymentDate, long daysOverdue, LocalDate overdueSince,
                      CreditStatus creditStatus, int pendingPaymentCount, BigDecimal pendingPaymentAmount) {}
}
