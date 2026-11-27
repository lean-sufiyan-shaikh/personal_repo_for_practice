package com.ppms.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The oldest-bills-first rule for one credit account (a standalone client, or a parent with its sub-accounts).
 * Every approved payment clears the oldest bills first, whatever its date, so the balance is made of the newest
 * bills. A bill is a credit sale (fuel or counter, or an opening balance), a live interest charge or a bounce charge; it falls due one billing cycle after
 * its date. The account is overdue while any bill past its due date still has an unpaid part, and its days overdue
 * are how far the oldest such bill is past due. A payment that leaves that bill partly unpaid changes neither.
 */
public final class CreditBillLedger {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public record Bill(LocalDate date, BigDecimal amount, boolean isSale) {}

    public record Payment(LocalDate date, BigDecimal amount) {}

    /**
     * @param unpaidBills    the unpaid part of each bill still owed, oldest first
     * @param overdueSince   first overdue day of the running unbroken overdue spell; null when not overdue. A payment
     *                       that clears the oldest bills while a newer one is already past due does not move it.
     */
    public record Standing(BigDecimal outstanding, List<Bill> unpaidBills, LocalDate oldestUnpaidSaleDate,
                           long daysOverdue, LocalDate overdueSince) {

        public static final Standing NOTHING_OWED = new Standing(ZERO, List.of(), null, 0, null);

        /** Disputed debits are owed but are not chased, so they add to the balance only. */
        public Standing plusDisputed(BigDecimal disputedAmount) {
            if (disputedAmount.signum() == 0) {
                return this;
            }
            return new Standing(outstanding.add(disputedAmount).setScale(2, RoundingMode.HALF_UP), unpaidBills,
                    oldestUnpaidSaleDate, daysOverdue, overdueSince);
        }

        /** A written-off account still shows what it owes but is never overdue. */
        public Standing notOverdue() {
            return new Standing(outstanding, unpaidBills, oldestUnpaidSaleDate, 0, null);
        }
    }

    private static final Comparator<Bill> OLDEST_FIRST = Comparator.comparing(Bill::date)
            .thenComparing(bill -> bill.isSale() ? 0 : 1);

    private CreditBillLedger() {
    }

    public static long cycleDays(BillingCycle billingCycle) {
        return billingCycle == BillingCycle.WEEKLY ? 7 : 30;
    }

    public static Standing standingOf(List<Bill> bills, List<Payment> payments, BillingCycle billingCycle,
                                      LocalDate today) {
        List<Bill> oldestFirst = bills.stream().sorted(OLDEST_FIRST).toList();
        BigDecimal paid = payments.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal billed = oldestFirst.stream().map(Bill::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = billed.subtract(paid).setScale(2, RoundingMode.HALF_UP);

        List<Bill> unpaidBills = unpaidParts(oldestFirst, paid);
        if (unpaidBills.isEmpty()) {
            return new Standing(outstanding, List.of(), null, 0, null);
        }
        long cycleDays = cycleDays(billingCycle);
        LocalDate oldestUnpaidSaleDate = unpaidBills.stream().filter(Bill::isSale).map(Bill::date).findFirst().orElse(null);
        long daysOverdue = Math.max(0, ChronoUnit.DAYS.between(unpaidBills.get(0).date(), today) - cycleDays);
        LocalDate overdueSince = daysOverdue > 0 ? overdueSince(oldestFirst, payments, cycleDays, today) : null;
        return new Standing(outstanding, unpaidBills, oldestUnpaidSaleDate, daysOverdue, overdueSince);
    }

    private static List<Bill> unpaidParts(List<Bill> oldestFirst, BigDecimal paid) {
        List<Bill> unpaid = new ArrayList<>();
        BigDecimal leftToClear = paid;
        for (Bill bill : oldestFirst) {
            BigDecimal cleared = bill.amount().min(leftToClear.max(BigDecimal.ZERO));
            leftToClear = leftToClear.subtract(cleared);
            BigDecimal unpaidPart = bill.amount().subtract(cleared);
            if (unpaidPart.signum() > 0) {
                unpaid.add(new Bill(bill.date(), unpaidPart.setScale(2, RoundingMode.HALF_UP), bill.isSale()));
            }
        }
        return unpaid;
    }

    /**
     * Replays the days on which the account's state can change (a bill falling due, a payment) and keeps the first
     * day of the last spell in which some past-due bill was not cleared by the payments made up to that day.
     * A payment dated after today counts as paid today.
     */
    private static LocalDate overdueSince(List<Bill> oldestFirst, List<Payment> payments, long cycleDays,
                                          LocalDate today) {
        List<Payment> byDate = payments.stream()
                .map(payment -> payment.date().isAfter(today) ? new Payment(today, payment.amount()) : payment)
                .sorted(Comparator.comparing(Payment::date))
                .toList();
        TreeSet<LocalDate> changeDays = new TreeSet<>();
        oldestFirst.forEach(bill -> changeDays.add(bill.date().plusDays(cycleDays + 1)));
        byDate.forEach(payment -> changeDays.add(payment.date()));

        BigDecimal billedUpToLatestDue = BigDecimal.ZERO;
        BigDecimal paidSoFar = BigDecimal.ZERO;
        int nextBill = 0;
        int nextPayment = 0;
        LocalDate spellStart = null;
        for (LocalDate day : changeDays.headSet(today, true)) {
            while (nextBill < oldestFirst.size() && !oldestFirst.get(nextBill).date().plusDays(cycleDays + 1).isAfter(day)) {
                billedUpToLatestDue = billedUpToLatestDue.add(oldestFirst.get(nextBill).amount());
                nextBill++;
            }
            while (nextPayment < byDate.size() && !byDate.get(nextPayment).date().isAfter(day)) {
                paidSoFar = paidSoFar.add(byDate.get(nextPayment).amount());
                nextPayment++;
            }
            boolean overdue = paidSoFar.compareTo(billedUpToLatestDue) < 0;
            if (!overdue) {
                spellStart = null;
            } else if (spellStart == null) {
                spellStart = day;
            }
        }
        return spellStart;
    }
}
