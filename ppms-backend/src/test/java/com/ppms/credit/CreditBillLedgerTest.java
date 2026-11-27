package com.ppms.credit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditBillLedgerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void tokenPaymentLeavesTheOldestBillOverdueAndTheSpellUnchanged() {
        List<CreditBillLedger.Bill> bills = List.of(sale(60, "300.00"));

        CreditBillLedger.Standing unpaid = standingOf(bills, List.of());
        CreditBillLedger.Standing afterToken = standingOf(bills, List.of(payment(1, "50.00")));

        assertThat(unpaid.daysOverdue()).isEqualTo(30);
        assertThat(afterToken.daysOverdue()).isEqualTo(30);
        assertThat(afterToken.overdueSince()).isEqualTo(unpaid.overdueSince()).isEqualTo(TODAY.minusDays(29));
        assertThat(afterToken.outstanding()).isEqualByComparingTo("250.00");
    }

    @Test
    void payingTheOldBillsInFullClearsTheOverdue() {
        CreditBillLedger.Standing standing = standingOf(List.of(sale(60, "300.00"), sale(5, "100.00")),
                List.of(payment(2, "300.00")));

        assertThat(standing.daysOverdue()).isZero();
        assertThat(standing.overdueSince()).isNull();
        assertThat(standing.oldestUnpaidSaleDate()).isEqualTo(TODAY.minusDays(5));
        assertThat(standing.unpaidBills()).extracting(CreditBillLedger.Bill::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("100.00"));
    }

    @Test
    void partPaymentCoveringTheOldestBillMovesTheDaysOverdueToTheNextBill() {
        List<CreditBillLedger.Bill> bills = List.of(sale(50, "100.00"), sale(40, "200.00"));

        CreditBillLedger.Standing standing = standingOf(bills, List.of(payment(0, "150.00")));

        assertThat(standing.daysOverdue()).isEqualTo(10);
        assertThat(standing.oldestUnpaidSaleDate()).isEqualTo(TODAY.minusDays(40));
        assertThat(standing.overdueSince()).isEqualTo(TODAY.minusDays(19));
    }

    @Test
    void paymentThatEndedAnEarlierSpellStartsANewOneAtTheNextBillsDueDate() {
        List<CreditBillLedger.Bill> bills = List.of(sale(80, "100.00"), sale(38, "100.00"));

        CreditBillLedger.Standing standing = standingOf(bills, List.of(payment(20, "100.00")));

        assertThat(standing.daysOverdue()).isEqualTo(8);
        assertThat(standing.overdueSince()).isEqualTo(TODAY.minusDays(7));
    }

    @Test
    void unpaidInterestIsABillButItDoesNotCountAsTheOldestUnpaidSale() {
        CreditBillLedger.Standing standing = standingOf(
                List.of(sale(70, "500.00"), new CreditBillLedger.Bill(TODAY.minusDays(40), new BigDecimal("12.00"), false)),
                List.of(payment(3, "500.00")));

        assertThat(standing.daysOverdue()).isEqualTo(10);
        assertThat(standing.oldestUnpaidSaleDate()).isNull();
        assertThat(standing.outstanding()).isEqualByComparingTo("12.00");
    }

    @Test
    void weeklyCycleFallsDueAfterSevenDaysAndOverpaymentOwesNothing() {
        CreditBillLedger.Standing weekly = CreditBillLedger.standingOf(List.of(sale(10, "100.00")), List.of(),
                BillingCycle.WEEKLY, TODAY);
        CreditBillLedger.Standing overpaid = standingOf(List.of(sale(90, "100.00")), List.of(payment(1, "150.00")));

        assertThat(weekly.daysOverdue()).isEqualTo(3);
        assertThat(overpaid.daysOverdue()).isZero();
        assertThat(overpaid.unpaidBills()).isEmpty();
        assertThat(overpaid.outstanding()).isEqualByComparingTo("-50.00");
    }

    private static CreditBillLedger.Standing standingOf(List<CreditBillLedger.Bill> bills,
                                                       List<CreditBillLedger.Payment> payments) {
        return CreditBillLedger.standingOf(bills, payments, BillingCycle.MONTHLY, TODAY);
    }

    private static CreditBillLedger.Bill sale(int daysAgo, String amount) {
        return new CreditBillLedger.Bill(TODAY.minusDays(daysAgo), new BigDecimal(amount), true);
    }

    private static CreditBillLedger.Payment payment(int daysAgo, String amount) {
        return new CreditBillLedger.Payment(TODAY.minusDays(daysAgo), new BigDecimal(amount));
    }
}
