package com.ppms.supplier;

import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.inventory.ShortageClaim;
import com.ppms.inventory.ShortageClaimRepository;
import com.ppms.inventory.ShortageClaimStatus;
import com.ppms.inventory.TankerDelivery;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.supplier.SupplierLedgerCalculator.Invoice;
import com.ppms.supplier.SupplierLedgerCalculator.Ledger;
import com.ppms.supplier.SupplierLedgerResponses.LedgerList;
import com.ppms.supplier.SupplierLedgerResponses.SupplierBalance;
import com.ppms.supplier.SupplierLedgerResponses.SupplierLedger;
import com.ppms.supplier.SupplierLedgerResponses.UnassignedDeliveries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Supplier balances read from tanker deliveries, payments and settled shortage claims. The pump-wide list loads each
 * of those once and splits them by supplier in memory, so it costs the same number of queries for any supplier count.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierLedgerService {

    private final FuelSupplierRepository supplierRepository;
    private final FuelSupplierPaymentRepository paymentRepository;
    private final TankerDeliveryRepository deliveryRepository;
    private final ShortageClaimRepository claimRepository;
    private final BusinessClock businessClock;

    public LedgerList balancesOf(Long pumpId) {
        ZoneId zone = businessClock.zone();
        LocalDate today = businessClock.today();
        List<TankerDelivery> deliveries = deliveryRepository.findByPumpId(pumpId);
        Map<Long, List<TankerDelivery>> deliveriesBySupplier = deliveries.stream()
                .filter(delivery -> delivery.getSupplierId() != null)
                .collect(Collectors.groupingBy(TankerDelivery::getSupplierId));
        Map<Long, List<FuelSupplierPayment>> paymentsBySupplier = paymentRepository
                .findByPumpIdAndVoidStatus(pumpId, FuelSupplierPayment.ACTIVE).stream()
                .collect(Collectors.groupingBy(FuelSupplierPayment::getSupplierId));
        Map<Long, TankerDelivery> deliveryById = deliveries.stream()
                .collect(Collectors.toMap(TankerDelivery::getId, delivery -> delivery));
        Map<Long, List<ShortageClaim>> claimsBySupplier = new HashMap<>();
        for (ShortageClaim claim : claimRepository.findByPumpIdAndStatus(pumpId, ShortageClaimStatus.SETTLED)) {
            TankerDelivery delivery = deliveryById.get(claim.getTankerDeliveryId());
            if (delivery != null && delivery.getSupplierId() != null) {
                claimsBySupplier.computeIfAbsent(delivery.getSupplierId(), id -> new ArrayList<>()).add(claim);
            }
        }
        Map<Long, String> invoiceByDeliveryId = invoiceReferencesOf(deliveries);

        List<SupplierBalance> balances = supplierRepository.findByPumpIdOrderByNameAsc(pumpId).stream()
                .map(supplier -> {
                    List<TankerDelivery> supplierDeliveries = deliveriesBySupplier.getOrDefault(supplier.getId(), List.of());
                    List<FuelSupplierPayment> payments = paymentsBySupplier.getOrDefault(supplier.getId(), List.of());
                    Ledger ledger = SupplierLedgerCalculator.build(supplier,
                            SupplierLedgerCalculator.invoicesOf(supplierDeliveries, zone), payments,
                            claimsBySupplier.getOrDefault(supplier.getId(), List.of()), invoiceByDeliveryId, today);
                    return toBalance(supplier, ledger, latestDeliveryDate(supplierDeliveries, zone),
                            payments.stream().map(FuelSupplierPayment::getPaymentDate).max(Comparator.naturalOrder())
                                    .orElse(null));
                })
                .toList();

        List<Invoice> unassignedInvoices = SupplierLedgerCalculator.invoicesOf(
                deliveries.stream().filter(delivery -> delivery.getSupplierId() == null).toList(), zone);
        UnassignedDeliveries unassigned = new UnassignedDeliveries(unassignedInvoices.size(),
                unassignedInvoices.stream().map(Invoice::amount).reduce(SupplierLedgerCalculator.ZERO, BigDecimal::add));
        return new LedgerList(balances, unassigned,
                balances.stream().map(SupplierBalance::outstanding).reduce(SupplierLedgerCalculator.ZERO, BigDecimal::add),
                balances.stream().map(SupplierBalance::advance).reduce(SupplierLedgerCalculator.ZERO, BigDecimal::add));
    }

    public SupplierLedger ledgerOf(Long pumpId, Long supplierId) {
        FuelSupplier supplier = supplierRepository.findByIdAndPumpId(supplierId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SUPPLIER, "Supplier not found"));
        List<TankerDelivery> deliveries = deliveryRepository.findByPumpIdAndSupplierId(pumpId, supplierId);
        List<ShortageClaim> settledClaims = deliveries.isEmpty() ? List.of() : claimRepository
                .findByTankerDeliveryIdIn(deliveries.stream().map(TankerDelivery::getId).toList()).stream()
                .filter(claim -> claim.getStatus() == ShortageClaimStatus.SETTLED)
                .toList();
        LocalDate today = businessClock.today();
        Ledger ledger = SupplierLedgerCalculator.build(supplier,
                SupplierLedgerCalculator.invoicesOf(deliveries, businessClock.zone()),
                paymentRepository.findByPumpIdAndSupplierIdAndVoidStatus(pumpId, supplierId, FuelSupplierPayment.ACTIVE),
                settledClaims, invoiceReferencesOf(deliveries), today);
        return new SupplierLedger(supplier.getId(), supplier.getName(), supplier.isActive(), supplier.isDefaultSupplier(),
                supplier.getOpeningBalance(), supplier.getOpeningBalanceDate(), ledger.entries(), ledger.totalDebit(),
                ledger.totalCredit(), ledger.balance(), ledger.outstanding(), ledger.advance(), ledger.aging(), today);
    }

    private static SupplierBalance toBalance(FuelSupplier supplier, Ledger ledger, LocalDate lastDeliveryDate,
                                             LocalDate lastPaymentDate) {
        return new SupplierBalance(supplier.getId(), supplier.getName(), supplier.isActive(), supplier.isDefaultSupplier(),
                supplier.getOpeningBalance(), supplier.getOpeningBalanceDate(), ledger.totalDebit(), ledger.totalCredit(),
                ledger.outstanding(), ledger.advance(), ledger.aging(), lastDeliveryDate, lastPaymentDate);
    }

    private static LocalDate latestDeliveryDate(Collection<TankerDelivery> deliveries, ZoneId zone) {
        return deliveries.stream()
                .map(delivery -> delivery.getDeliveryDate().atZoneSameInstant(zone).toLocalDate())
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private static Map<Long, String> invoiceReferencesOf(List<TankerDelivery> deliveries) {
        return deliveries.stream()
                .filter(delivery -> delivery.getInvoiceReference() != null)
                .collect(Collectors.toMap(TankerDelivery::getId, TankerDelivery::getInvoiceReference));
    }
}
