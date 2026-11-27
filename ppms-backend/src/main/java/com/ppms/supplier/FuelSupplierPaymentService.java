package com.ppms.supplier;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.PaymentMode;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Payments to fuel suppliers. A payment is dated on a business day, so a closed day refuses it; a reference is used
 * once per supplier; a cash payment takes the money out of the safe, and voiding it puts the money back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FuelSupplierPaymentService {

    private static final String ENTITY_NAME = "FuelSupplierPayment";

    private final FuelSupplierPaymentRepository paymentRepository;
    private final FuelSupplierRepository supplierRepository;
    private final TankerDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final CashDrawerService cashDrawerService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public PagedResponse<SupplierPaymentResponse> list(Long pumpId, Long supplierId, int page, int size) {
        var pageable = PageRequests.of(page, size);
        Page<FuelSupplierPayment> payments = supplierId != null
                ? paymentRepository.findByPumpIdAndSupplierIdOrderByPaymentDateDesc(pumpId, supplierId, pageable)
                : paymentRepository.findByPumpIdOrderByPaymentDateDescCreatedAtDesc(pumpId, pageable);
        List<FuelSupplierPayment> content = payments.getContent();
        Set<Long> supplierIds = content.stream().map(FuelSupplierPayment::getSupplierId).collect(Collectors.toSet());
        Map<Long, String> supplierNameById = supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(FuelSupplier::getId, FuelSupplier::getName));
        Set<Long> userIds = content.stream().map(FuelSupplierPayment::getRecordedByUserId).collect(Collectors.toSet());
        Map<Long, String> userNameById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return PagedResponse.of(payments.map(payment -> toResponse(payment,
                supplierNameById.getOrDefault(payment.getSupplierId(), "Unknown"),
                userNameById.getOrDefault(payment.getRecordedByUserId(), "Unknown"))));
    }

    /** The pump row is locked so two payments with one reference, or two cash payments, cannot both pass the checks. */
    @Transactional
    public SupplierPaymentResponse record(Long pumpId, RecordSupplierPaymentRequest request, User currentUser) {
        FuelSupplier supplier = supplierRepository.findByIdAndPumpId(request.supplierId(), pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SUPPLIER, "Supplier not found"));
        if (!supplier.isActive()) {
            throw new BusinessException(ErrorCodes.SUPPLIER_INACTIVE, Map.of("supplierName", supplier.getName()),
                    supplier.getName() + " is inactive. Re-activate the supplier before recording a payment.");
        }
        if (request.deliveryId() != null && !deliveryRepository.existsByIdAndPumpId(request.deliveryId(), pumpId)) {
            throw new ResourceNotFoundException(EntityType.DELIVERY, "Delivery not found");
        }
        if (request.paymentDate().isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.SUPPLIER_PAYMENT_DATE_IN_FUTURE, Map.of(),
                    "A supplier payment cannot be dated in the future.");
        }
        closedDayGuard.requireOpen(pumpId, request.paymentDate());
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        String referenceNo = request.referenceNo() == null || request.referenceNo().isBlank()
                ? null : request.referenceNo().trim();
        if (referenceNo != null && paymentRepository.existsActiveWithReference(supplier.getId(), referenceNo)) {
            throw new BusinessException(ErrorCodes.SUPPLIER_PAYMENT_REFERENCE_DUPLICATE,
                    Map.of("referenceNo", referenceNo, "supplierName", supplier.getName()),
                    "A payment to " + supplier.getName() + " with reference " + referenceNo + " is already recorded.");
        }

        FuelSupplierPayment payment = paymentRepository.save(FuelSupplierPayment.builder()
                .pumpId(pumpId)
                .supplierId(supplier.getId())
                .deliveryId(request.deliveryId())
                .amount(request.amount().setScale(2, RoundingMode.HALF_UP))
                .paymentDate(request.paymentDate())
                .paymentMode(request.paymentMode())
                .referenceNo(referenceNo)
                .notes(request.notes())
                .recordedByUserId(currentUser.getId())
                .build());
        if (payment.getPaymentMode() == PaymentMode.CASH) {
            CashEvent cashOut = cashDrawerService.recordAutomaticCashOut(pumpId, payment.getAmount(), payment.getPaymentDate(),
                    "Cash paid to supplier " + supplier.getName() + " (supplier payment #" + payment.getId() + ")", currentUser);
            payment.setCashEventId(cashOut.getId());
            payment = paymentRepository.save(payment);
        }

        auditService.log(pumpId, AuditAction.SUPPLIER_PAYMENT_RECORDED, ENTITY_NAME, payment.getId().toString(),
                "Payment ₹" + payment.getAmount() + " to " + supplier.getName() + " (" + payment.getPaymentMode() + ")"
                        + (referenceNo != null ? " ref=" + referenceNo : ""),
                currentUser);
        log.info("Supplier payment recorded: pump={} supplier={} payment={} amount={} mode={} by={}",
                pumpId, supplier.getId(), payment.getId(), payment.getAmount(), payment.getPaymentMode(), currentUser.getId());
        return toResponse(payment, supplier.getName(), currentUser.getFullName());
    }

    @Transactional
    public SupplierPaymentResponse voidPayment(Long pumpId, Long paymentId, String reason, User currentUser) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        FuelSupplierPayment payment = paymentRepository.findByIdAndPumpId(paymentId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SUPPLIER_PAYMENT, "Supplier payment not found"));
        if (FuelSupplierPayment.VOIDED.equals(payment.getVoidStatus())) {
            throw new BusinessException(ErrorCodes.SUPPLIER_PAYMENT_ALREADY_VOIDED, Map.of(),
                    "This supplier payment is already voided.");
        }
        closedDayGuard.requireOpen(pumpId, payment.getPaymentDate());
        String trimmedReason = reason.trim();
        payment.setVoidStatus(FuelSupplierPayment.VOIDED);
        payment.setVoidReason(trimmedReason);
        payment.setVoidedByUserId(currentUser.getId());
        payment.setVoidedAt(businessClock.now());
        payment = paymentRepository.save(payment);
        if (payment.getCashEventId() != null) {
            cashDrawerService.reverseAutomaticEvent(pumpId, payment.getCashEventId(),
                    "Supplier payment #" + payment.getId() + " voided: " + trimmedReason, currentUser);
        }

        String supplierName = supplierRepository.findById(payment.getSupplierId()).map(FuelSupplier::getName).orElse("Unknown");
        auditService.log(pumpId, AuditAction.SUPPLIER_PAYMENT_VOIDED, ENTITY_NAME, payment.getId().toString(),
                "Voided payment ₹" + payment.getAmount() + " to " + supplierName + " on " + payment.getPaymentDate()
                        + ". Reason: " + trimmedReason,
                currentUser);
        log.info("Supplier payment voided: pump={} payment={} amount={} by={}",
                pumpId, payment.getId(), payment.getAmount(), currentUser.getId());
        String recordedByName = userRepository.findById(payment.getRecordedByUserId()).map(User::getFullName).orElse("Unknown");
        return toResponse(payment, supplierName, recordedByName);
    }

    private static SupplierPaymentResponse toResponse(FuelSupplierPayment payment, String supplierName, String recordedByName) {
        return SupplierPaymentResponse.builder()
                .id(payment.getId())
                .pumpId(payment.getPumpId())
                .supplierId(payment.getSupplierId())
                .supplierName(supplierName)
                .deliveryId(payment.getDeliveryId())
                .amount(payment.getAmount())
                .paymentDate(payment.getPaymentDate())
                .paymentMode(payment.getPaymentMode().name())
                .referenceNo(payment.getReferenceNo())
                .notes(payment.getNotes())
                .recordedByUserId(payment.getRecordedByUserId())
                .recordedByName(recordedByName)
                .createdAt(payment.getCreatedAt())
                .voidStatus(payment.getVoidStatus())
                .voidReason(payment.getVoidReason())
                .voidedAt(payment.getVoidedAt())
                .cashEventId(payment.getCashEventId())
                .build();
    }
}
