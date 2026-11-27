package com.ppms.supplier;

import com.ppms.inventory.ShortageClaim;
import com.ppms.inventory.TankerDelivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds a supplier's ledger: the opening balance and each tanker invoice owed (debits), payments and credit notes from
 * settled shortage claims (credits), with a running balance. Credits pay off the oldest debits first, and what stays
 * unpaid is aged by the debit's date.
 */
public final class SupplierLedgerCalculator {

    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private SupplierLedgerCalculator() {
    }

    public enum EntryType { OPENING_BALANCE, DELIVERY, CREDIT_NOTE, PAYMENT }

    public record Entry(LocalDate date, EntryType type, String reference, String description, BigDecimal debit,
                        BigDecimal credit, BigDecimal balance, Long paymentId, String paymentMode, List<Long> deliveryIds) {
    }

    public record Aging(BigDecimal days0To15, BigDecimal days16To30, BigDecimal days31To60, BigDecimal over60Days) {
    }

    public record Ledger(List<Entry> entries, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal balance,
                         BigDecimal outstanding, BigDecimal advance, Aging aging) {
    }

    /** What one tanker invoice is owed for: its bill total when typed in, else litres × cost of each tank line. */
    public record Invoice(LocalDate date, String reference, BigDecimal amount, String description, List<Long> deliveryIds) {
    }

    public static Ledger build(FuelSupplier supplier, List<Invoice> invoices, List<FuelSupplierPayment> activePayments,
                               List<ShortageClaim> settledClaims, Map<Long, String> invoiceByDeliveryId, LocalDate today) {
        List<Entry> unsorted = new ArrayList<>();
        BigDecimal opening = supplier.getOpeningBalance() != null ? supplier.getOpeningBalance() : ZERO;
        if (opening.signum() != 0) {
            unsorted.add(new Entry(supplier.getOpeningBalanceDate(), EntryType.OPENING_BALANCE, null, null,
                    opening.signum() > 0 ? money(opening) : ZERO, opening.signum() < 0 ? money(opening.negate()) : ZERO,
                    null, null, null, List.of()));
        }
        for (Invoice invoice : invoices) {
            unsorted.add(new Entry(invoice.date(), EntryType.DELIVERY, invoice.reference(), invoice.description(),
                    money(invoice.amount()), ZERO, null, null, null, invoice.deliveryIds()));
        }
        for (ShortageClaim claim : settledClaims) {
            unsorted.add(new Entry(claim.getCreditNoteDate(), EntryType.CREDIT_NOTE, claim.getCreditNoteReference(),
                    invoiceByDeliveryId.get(claim.getTankerDeliveryId()), ZERO, money(claim.getCreditNoteAmount()),
                    null, null, null, List.of(claim.getTankerDeliveryId())));
        }
        for (FuelSupplierPayment payment : activePayments) {
            unsorted.add(new Entry(payment.getPaymentDate(), EntryType.PAYMENT, payment.getReferenceNo(),
                    payment.getNotes(), ZERO, money(payment.getAmount()), null, payment.getId(),
                    payment.getPaymentMode().name(),
                    payment.getDeliveryId() != null ? List.of(payment.getDeliveryId()) : List.of()));
        }
        unsorted.sort(Comparator.comparing(Entry::date, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Entry::type));

        List<Entry> entries = new ArrayList<>();
        BigDecimal balance = ZERO;
        BigDecimal totalDebit = ZERO;
        BigDecimal totalCredit = ZERO;
        for (Entry entry : unsorted) {
            balance = balance.add(entry.debit()).subtract(entry.credit());
            totalDebit = totalDebit.add(entry.debit());
            totalCredit = totalCredit.add(entry.credit());
            entries.add(new Entry(entry.date(), entry.type(), entry.reference(), entry.description(), entry.debit(),
                    entry.credit(), balance, entry.paymentId(), entry.paymentMode(), entry.deliveryIds()));
        }
        return new Ledger(entries, totalDebit, totalCredit, balance, balance.max(ZERO), balance.negate().max(ZERO),
                agingOf(entries, totalCredit, today));
    }

    /**
     * Groups tanker deliveries into invoices. Lines of one invoice share their bill total, so it is counted once; a
     * delivery without an invoice reference is an invoice of its own.
     */
    public static List<Invoice> invoicesOf(List<TankerDelivery> deliveries, ZoneId zone) {
        Map<String, List<TankerDelivery>> linesByInvoice = deliveries.stream()
                .sorted(Comparator.comparing(TankerDelivery::getDeliveryDate).thenComparing(TankerDelivery::getId))
                .collect(Collectors.groupingBy(delivery -> invoiceKey(delivery, zone), LinkedHashMap::new,
                        Collectors.toList()));
        List<Invoice> invoices = new ArrayList<>();
        linesByInvoice.values().forEach(lines -> invoices.add(invoiceOf(lines, zone)));
        return invoices;
    }

    private static Invoice invoiceOf(List<TankerDelivery> lines, ZoneId zone) {
        TankerDelivery first = lines.get(0);
        BigDecimal billTotal = lines.stream().map(TankerDelivery::getBillTotal).filter(Objects::nonNull).findFirst()
                .orElse(null);
        BigDecimal amount = billTotal != null ? money(billTotal) : lines.stream()
                .map(line -> money(line.getQuantityDelivered().multiply(line.getCostPricePerUnit())))
                .reduce(ZERO, BigDecimal::add);
        String description = lines.stream()
                .map(line -> line.getFuelType().name() + " " + line.getQuantityDelivered().setScale(2, RoundingMode.HALF_UP)
                        .toPlainString() + " L")
                .collect(Collectors.joining(", "));
        return new Invoice(first.getDeliveryDate().atZoneSameInstant(zone).toLocalDate(), first.getInvoiceReference(),
                amount, description, lines.stream().map(TankerDelivery::getId).toList());
    }

    private static String invoiceKey(TankerDelivery delivery, ZoneId zone) {
        if (delivery.getInvoiceReference() == null || delivery.getInvoiceReference().isBlank()) {
            return "#" + delivery.getId();
        }
        return delivery.getSupplierId() + "|" + delivery.getDeliveryDate().atZoneSameInstant(zone).toLocalDate() + "|"
                + delivery.getInvoiceReference().trim().toLowerCase();
    }

    private static Aging agingOf(List<Entry> entries, BigDecimal totalCredit, LocalDate today) {
        BigDecimal creditLeft = totalCredit;
        BigDecimal[] buckets = {ZERO, ZERO, ZERO, ZERO};
        for (Entry entry : entries) {
            if (entry.debit().signum() == 0) {
                continue;
            }
            BigDecimal paid = entry.debit().min(creditLeft);
            creditLeft = creditLeft.subtract(paid);
            BigDecimal unpaid = entry.debit().subtract(paid);
            if (unpaid.signum() > 0) {
                int bucket = bucketOf(entry.date() == null ? 0 : ChronoUnit.DAYS.between(entry.date(), today));
                buckets[bucket] = buckets[bucket].add(unpaid);
            }
        }
        return new Aging(buckets[0], buckets[1], buckets[2], buckets[3]);
    }

    private static int bucketOf(long ageDays) {
        if (ageDays <= 15) {
            return 0;
        }
        if (ageDays <= 30) {
            return 1;
        }
        return ageDays <= 60 ? 2 : 3;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
