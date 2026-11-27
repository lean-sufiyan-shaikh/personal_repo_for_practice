package com.ppms.supplier;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    private static final String ENTITY_NAME = "FuelSupplier";

    private final FuelSupplierRepository supplierRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public List<FuelSupplier> getActiveSuppliers(Long pumpId) {
        return supplierRepository.findByPumpIdAndActiveTrueOrderByNameAsc(pumpId);
    }

    @Transactional
    public FuelSupplier createSupplier(Long pumpId, UpsertSupplierRequest req, User currentUser) {
        FuelSupplier supplier = FuelSupplier.builder().pumpId(pumpId).build();
        applyDetails(supplier, req);
        supplier = supplierRepository.save(supplier);
        applyOpeningBalance(supplier, req, currentUser);
        return supplierRepository.save(supplier);
    }

    @Transactional
    public FuelSupplier updateSupplier(Long pumpId, Long supplierId, UpsertSupplierRequest req, User currentUser) {
        FuelSupplier supplier = findSupplier(pumpId, supplierId);
        applyDetails(supplier, req);
        applyOpeningBalance(supplier, req, currentUser);
        return supplierRepository.save(supplier);
    }

    /** Soft delete, so past deliveries keep their supplier; a deactivated supplier is no longer the default. */
    @Transactional
    public void deactivateSupplier(Long pumpId, Long supplierId) {
        FuelSupplier supplier = findSupplier(pumpId, supplierId);
        supplier.setActive(false);
        supplier.setDefaultSupplier(false);
        supplierRepository.save(supplier);
    }

    /** The pump row is locked so two owners making different suppliers the default cannot both pass. */
    @Transactional
    public FuelSupplier makeDefault(Long pumpId, Long supplierId, User currentUser) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        FuelSupplier supplier = findSupplier(pumpId, supplierId);
        requireActive(supplier);
        supplierRepository.findByPumpIdAndDefaultSupplierTrue(pumpId)
                .filter(current -> !current.getId().equals(supplier.getId()))
                .ifPresent(current -> {
                    current.setDefaultSupplier(false);
                    supplierRepository.saveAndFlush(current);
                });
        supplier.setDefaultSupplier(true);
        FuelSupplier saved = supplierRepository.save(supplier);
        auditService.log(pumpId, AuditAction.SUPPLIER_DEFAULT_SET, ENTITY_NAME, saved.getId().toString(),
                saved.getName() + " is now the default supplier for tanker deliveries", currentUser);
        return saved;
    }

    /**
     * The supplier a new tanker delivery is booked to: the one picked, which must be an active supplier of the pump,
     * else the pump's default supplier, else none.
     */
    @Transactional(readOnly = true)
    public FuelSupplier supplierForDelivery(Long pumpId, Long requestedSupplierId) {
        if (requestedSupplierId == null) {
            return supplierRepository.findByPumpIdAndDefaultSupplierTrue(pumpId).orElse(null);
        }
        FuelSupplier supplier = findSupplier(pumpId, requestedSupplierId);
        requireActive(supplier);
        return supplier;
    }

    @Transactional(readOnly = true)
    public String supplierNameOf(Long supplierId) {
        return supplierId == null ? null : supplierRepository.findById(supplierId).map(FuelSupplier::getName).orElse(null);
    }

    private FuelSupplier findSupplier(Long pumpId, Long supplierId) {
        return supplierRepository.findByIdAndPumpId(supplierId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SUPPLIER, "Supplier not found"));
    }

    private static void requireActive(FuelSupplier supplier) {
        if (!supplier.isActive()) {
            throw new BusinessException(ErrorCodes.SUPPLIER_INACTIVE, Map.of("supplierName", supplier.getName()),
                    supplier.getName() + " is inactive. Re-activate the supplier first.");
        }
    }

    private void applyDetails(FuelSupplier supplier, UpsertSupplierRequest req) {
        supplier.setName(req.getName().trim());
        supplier.setContactName(req.getContactName());
        supplier.setPhone(req.getPhone());
        supplier.setEmail(req.getEmail());
        supplier.setNotes(req.getNotes());
    }

    private void applyOpeningBalance(FuelSupplier supplier, UpsertSupplierRequest req, User currentUser) {
        if (req.getOpeningBalance() == null) {
            return;
        }
        BigDecimal balance = req.getOpeningBalance().setScale(2, RoundingMode.HALF_UP);
        LocalDate balanceDate = balance.signum() == 0 ? req.getOpeningBalanceDate() : requireBalanceDate(req.getOpeningBalanceDate());
        if (balance.compareTo(supplier.getOpeningBalance()) == 0
                && Objects.equals(balanceDate, supplier.getOpeningBalanceDate())) {
            return;
        }
        supplier.setOpeningBalance(balance);
        supplier.setOpeningBalanceDate(balanceDate);
        auditService.log(supplier.getPumpId(), AuditAction.SUPPLIER_OPENING_BALANCE_SET, ENTITY_NAME,
                supplier.getId().toString(),
                "Opening balance of " + supplier.getName() + " set to ₹" + balance
                        + (balanceDate != null ? " as of " + balanceDate : ""),
                currentUser);
        log.info("Supplier opening balance set: pump={} supplier={} balance={} date={} by={}",
                supplier.getPumpId(), supplier.getId(), balance, balanceDate, currentUser.getId());
    }

    private LocalDate requireBalanceDate(LocalDate date) {
        if (date == null) {
            throw new BusinessException(ErrorCodes.SUPPLIER_OPENING_BALANCE_DATE_REQUIRED, Map.of(),
                    "An opening balance needs the date it stood on.");
        }
        if (date.isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.SUPPLIER_OPENING_BALANCE_DATE_IN_FUTURE, Map.of(),
                    "The opening balance date cannot be in the future.");
        }
        return date;
    }
}
