package com.ppms.supplier;

import com.ppms.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pumps")
@RequiredArgsConstructor
public class SupplierController {

    private final FuelSupplierRepository supplierRepository;

    /**
     * GET /api/pumps/{pumpId}/suppliers
     * Returns all active suppliers for a pump, alphabetically sorted.
     */
    @GetMapping("/{pumpId}/suppliers")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public List<FuelSupplier> getSuppliers(
            @PathVariable Long pumpId) {
        return supplierRepository.findByPumpIdAndActiveTrueOrderByNameAsc(pumpId);
    }

    /**
     * POST /api/pumps/{pumpId}/suppliers
     * Creates a new supplier for the pump.
     */
    @PostMapping("/{pumpId}/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public FuelSupplier createSupplier(
            @PathVariable Long pumpId,
            @Valid @RequestBody UpsertSupplierRequest req) {
        FuelSupplier supplier = FuelSupplier.builder()
                .pumpId(pumpId)
                .name(req.getName().trim())
                .contactName(req.getContactName())
                .phone(req.getPhone())
                .email(req.getEmail())
                .notes(req.getNotes())
                .build();

        return supplierRepository.save(supplier);
    }

    /**
     * PUT /api/pumps/{pumpId}/suppliers/{supplierId}
     * Updates an existing supplier's details.
     */
    @PutMapping("/{pumpId}/suppliers/{supplierId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public FuelSupplier updateSupplier(
            @PathVariable Long pumpId,
            @PathVariable Long supplierId,
            @Valid @RequestBody UpsertSupplierRequest req) {
        FuelSupplier supplier = supplierRepository.findByIdAndPumpId(supplierId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));

        supplier.setName(req.getName().trim());
        supplier.setContactName(req.getContactName());
        supplier.setPhone(req.getPhone());
        supplier.setEmail(req.getEmail());
        supplier.setNotes(req.getNotes());

        return supplierRepository.save(supplier);
    }

    /**
     * DELETE /api/pumps/{pumpId}/suppliers/{supplierId}
     * Soft-deletes a supplier (sets active=false). This preserves historical delivery records.
     * Only OWNER can deactivate a supplier.
     */
    @DeleteMapping("/{pumpId}/suppliers/{supplierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void deactivateSupplier(
            @PathVariable Long pumpId,
            @PathVariable Long supplierId) {
        FuelSupplier supplier = supplierRepository.findByIdAndPumpId(supplierId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));

        supplier.setActive(false);
        supplierRepository.save(supplier);
    }
}
