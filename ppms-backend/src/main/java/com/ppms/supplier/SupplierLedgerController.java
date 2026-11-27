package com.ppms.supplier;

import com.ppms.supplier.SupplierLedgerResponses.LedgerList;
import com.ppms.supplier.SupplierLedgerResponses.SupplierLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the pump owes its fuel suppliers.
 *
 * GET /api/pumps/{pumpId}/supplier-ledger               — every supplier's balance and aging, plus unassigned deliveries
 * GET /api/pumps/{pumpId}/supplier-ledger/{supplierId}  — one supplier's ledger with a running balance
 */
@RestController
@RequestMapping("/api/pumps/{pumpId}/supplier-ledger")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
public class SupplierLedgerController {

    private final SupplierLedgerService ledgerService;

    @GetMapping
    public LedgerList getBalances(@PathVariable Long pumpId) {
        return ledgerService.balancesOf(pumpId);
    }

    @GetMapping("/{supplierId}")
    public SupplierLedger getLedger(@PathVariable Long pumpId, @PathVariable Long supplierId) {
        return ledgerService.ledgerOf(pumpId, supplierId);
    }
}
