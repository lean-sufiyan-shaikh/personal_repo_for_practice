package com.ppms.supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What the supplier ledger endpoints return. Balances are what the pump owes; an advance is what it paid ahead. */
public final class SupplierLedgerResponses {

    private SupplierLedgerResponses() {
    }

    public record SupplierBalance(Long supplierId, String supplierName, boolean active, boolean defaultSupplier,
                                  BigDecimal openingBalance, LocalDate openingBalanceDate, BigDecimal totalDebit,
                                  BigDecimal totalCredit, BigDecimal outstanding, BigDecimal advance,
                                  SupplierLedgerCalculator.Aging aging, LocalDate lastDeliveryDate,
                                  LocalDate lastPaymentDate) {
    }

    /** Tanker invoices not booked to any supplier, so they are in no supplier's balance. */
    public record UnassignedDeliveries(int invoiceCount, BigDecimal amount) {
    }

    public record LedgerList(List<SupplierBalance> suppliers, UnassignedDeliveries unassigned, BigDecimal totalOutstanding,
                             BigDecimal totalAdvance) {
    }

    public record SupplierLedger(Long supplierId, String supplierName, boolean active, boolean defaultSupplier,
                                 BigDecimal openingBalance, LocalDate openingBalanceDate,
                                 List<SupplierLedgerCalculator.Entry> entries, BigDecimal totalDebit,
                                 BigDecimal totalCredit, BigDecimal balance, BigDecimal outstanding, BigDecimal advance,
                                 SupplierLedgerCalculator.Aging aging, LocalDate asOf) {
    }
}
