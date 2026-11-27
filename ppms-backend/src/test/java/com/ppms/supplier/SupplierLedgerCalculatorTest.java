package com.ppms.supplier;

import com.ppms.credit.PaymentMode;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.ShortageClaim;
import com.ppms.inventory.ShortageClaimStatus;
import com.ppms.inventory.TankerDelivery;
import com.ppms.supplier.SupplierLedgerCalculator.EntryType;
import com.ppms.supplier.SupplierLedgerCalculator.Invoice;
import com.ppms.supplier.SupplierLedgerCalculator.Ledger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierLedgerCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Test
    void linesOfOneInvoiceShareTheirBillTotalAndOthersAddLitresTimesCost() {
        List<Invoice> invoices = SupplierLedgerCalculator.invoicesOf(List.of(
                delivery(1L, "INV-1", TODAY.minusDays(3), "1000", "90", "250000.00"),
                delivery(2L, "INV-1", TODAY.minusDays(3), "1500", "85", "250000.00"),
                delivery(3L, "INV-2", TODAY.minusDays(1), "1000", "90.555", null)), ZONE);

        assertThat(invoices).extracting(Invoice::reference).containsExactly("INV-1", "INV-2");
        assertThat(invoices.get(0).amount()).isEqualByComparingTo("250000.00");
        assertThat(invoices.get(0).deliveryIds()).containsExactly(1L, 2L);
        assertThat(invoices.get(1).amount()).isEqualByComparingTo("90555.00");
    }

    @Test
    void paymentsAndCreditNotesPayOffTheOldestDebitsFirstAndTheRestIsAged() {
        FuelSupplier supplier = supplier("10000.00", TODAY.minusDays(90));
        List<Invoice> invoices = List.of(
                new Invoice(TODAY.minusDays(40), "INV-OLD", new BigDecimal("50000.00"), "PETROL", List.of(1L)),
                new Invoice(TODAY.minusDays(20), "INV-MID", new BigDecimal("30000.00"), "PETROL", List.of(2L)),
                new Invoice(TODAY.minusDays(5), "INV-NEW", new BigDecimal("20000.00"), "DIESEL", List.of(3L)));
        List<FuelSupplierPayment> payments = List.of(payment(10L, "45000.00", TODAY.minusDays(10)));
        List<ShortageClaim> claims = List.of(settledClaim(1L, "5000.00", TODAY.minusDays(30)));

        Ledger ledger = SupplierLedgerCalculator.build(supplier, invoices, payments, claims, Map.of(1L, "INV-OLD"), TODAY);

        assertThat(ledger.entries()).extracting(SupplierLedgerCalculator.Entry::type).containsExactly(
                EntryType.OPENING_BALANCE, EntryType.DELIVERY, EntryType.CREDIT_NOTE, EntryType.DELIVERY,
                EntryType.PAYMENT, EntryType.DELIVERY);
        assertThat(ledger.entries().get(2).description()).isEqualTo("INV-OLD");
        assertThat(ledger.entries()).extracting(entry -> entry.balance().toPlainString())
                .containsExactly("10000.00", "60000.00", "55000.00", "85000.00", "40000.00", "60000.00");
        assertThat(ledger.outstanding()).isEqualByComparingTo("60000.00");
        assertThat(ledger.advance()).isEqualByComparingTo("0.00");
        assertThat(ledger.aging().over60Days()).isEqualByComparingTo("0.00");
        assertThat(ledger.aging().days31To60()).isEqualByComparingTo("10000.00");
        assertThat(ledger.aging().days16To30()).isEqualByComparingTo("30000.00");
        assertThat(ledger.aging().days0To15()).isEqualByComparingTo("20000.00");
    }

    @Test
    void payingMoreThanOwedLeavesAnAdvanceAndNothingAged() {
        FuelSupplier supplier = supplier("-2000.00", TODAY.minusDays(70));
        List<Invoice> invoices = List.of(new Invoice(TODAY.minusDays(70), "INV-1", new BigDecimal("10000.00"), "PETROL",
                List.of(1L)));

        Ledger ledger = SupplierLedgerCalculator.build(supplier, invoices, List.of(payment(1L, "9000.00", TODAY)),
                List.of(), Map.of(), TODAY);

        assertThat(ledger.balance()).isEqualByComparingTo("-1000.00");
        assertThat(ledger.outstanding()).isEqualByComparingTo("0.00");
        assertThat(ledger.advance()).isEqualByComparingTo("1000.00");
        assertThat(ledger.aging().over60Days()).isEqualByComparingTo("0.00");
        assertThat(ledger.totalCredit()).isEqualByComparingTo("11000.00");
    }

    private static FuelSupplier supplier(String openingBalance, LocalDate openingDate) {
        return FuelSupplier.builder().id(1L).pumpId(1L).name("IOCL").active(true)
                .openingBalance(new BigDecimal(openingBalance)).openingBalanceDate(openingDate).build();
    }

    private static TankerDelivery delivery(Long id, String invoice, LocalDate date, String litres, String cost,
                                           String billTotal) {
        return TankerDelivery.builder().id(id).supplierId(1L).fuelType(FuelType.PETROL)
                .quantityDelivered(new BigDecimal(litres)).costPricePerUnit(new BigDecimal(cost))
                .billTotal(billTotal != null ? new BigDecimal(billTotal) : null)
                .deliveryDate(date.atStartOfDay(ZONE).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC))
                .invoiceReference(invoice).build();
    }

    private static FuelSupplierPayment payment(Long id, String amount, LocalDate date) {
        return FuelSupplierPayment.builder().id(id).supplierId(1L).amount(new BigDecimal(amount)).paymentDate(date)
                .paymentMode(PaymentMode.BANK_TRANSFER).build();
    }

    private static ShortageClaim settledClaim(Long deliveryId, String creditNote, LocalDate date) {
        return ShortageClaim.builder().tankerDeliveryId(deliveryId).status(ShortageClaimStatus.SETTLED)
                .creditNoteAmount(new BigDecimal(creditNote)).creditNoteDate(date).creditNoteReference("CN-1").build();
    }
}
