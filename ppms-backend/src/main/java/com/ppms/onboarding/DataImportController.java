package com.ppms.onboarding;

import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk import from the Setup templates. The import screen reads the spreadsheet in the browser and sends its rows;
 * every row is checked again here. A preview only reports; an import creates every new row in one transaction, or
 * nothing when any row has a problem.
 */
@RestController
@RequestMapping("/api/pumps/{pumpId}/imports")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
@RequiredArgsConstructor
public class DataImportController {

    private final StaffImportService staffImportService;
    private final CreditClientImportService creditClientImportService;
    private final ProductImportService productImportService;
    private final TankStockImportService tankStockImportService;

    @PostMapping("/staff/preview")
    public ResponseEntity<ImportReport> previewStaff(@PathVariable Long pumpId, @RequestBody StaffImportRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(staffImportService.preview(pumpId, request, currentUser));
    }

    /** The response carries each new member's temporary password once; it is not stored anywhere readable. */
    @PostMapping("/staff")
    public ResponseEntity<ImportReport> importStaff(@PathVariable Long pumpId, @RequestBody StaffImportRequest request,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(staffImportService.importRows(pumpId, request, currentUser));
    }

    @PostMapping("/credit-clients/preview")
    public ResponseEntity<ImportReport> previewCreditClients(@PathVariable Long pumpId,
                                                             @RequestBody CreditClientImportRequest request) {
        return ResponseEntity.ok(creditClientImportService.preview(pumpId, request));
    }

    @PostMapping("/credit-clients")
    public ResponseEntity<ImportReport> importCreditClients(@PathVariable Long pumpId,
                                                            @RequestBody CreditClientImportRequest request,
                                                            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(creditClientImportService.importRows(pumpId, request, currentUser));
    }

    @PostMapping("/products/preview")
    public ResponseEntity<ImportReport> previewProducts(@PathVariable Long pumpId, @RequestBody ProductImportRequest request) {
        return ResponseEntity.ok(productImportService.preview(pumpId, request));
    }

    @PostMapping("/products")
    public ResponseEntity<ImportReport> importProducts(@PathVariable Long pumpId, @RequestBody ProductImportRequest request,
                                                       @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(productImportService.importRows(pumpId, request, currentUser));
    }

    @PostMapping("/tank-stock/preview")
    public ResponseEntity<ImportReport> previewTankStock(@PathVariable Long pumpId, @RequestBody TankStockImportRequest request) {
        return ResponseEntity.ok(tankStockImportService.preview(pumpId, request));
    }

    @PostMapping("/tank-stock")
    public ResponseEntity<ImportReport> importTankStock(@PathVariable Long pumpId, @RequestBody TankStockImportRequest request,
                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(tankStockImportService.importRows(pumpId, request, currentUser));
    }
}
