package com.ppms.inventory;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.UndergroundTank;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Raises a shortage claim when a delivery's receipt check finds it short beyond tolerance, keeps it in step with
 * corrections to the delivery while it is open, and records the supplier's answer: a credit note or a rejection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortageClaimService {

    private static final String ENTITY_NAME = "ShortageClaim";

    private final ShortageClaimRepository claimRepository;
    private final TankerDeliveryRepository deliveryRepository;
    private final InventoryLotRepository lotRepository;
    private final LotCostCorrectionService lotCostCorrectionService;
    private final ShortageClaimQueryService queryService;
    private final NotificationDedupService dedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    /**
     * Makes the delivery's claim match its receipt check: raised once when short, refreshed while open, and withdrawn
     * when a correction leaves the delivery no longer short. Returns the claim, or null when the delivery has none.
     */
    @Transactional
    public ShortageClaim syncWithDelivery(TankerDelivery delivery, UndergroundTank tank, User currentUser) {
        Optional<ShortageClaim> existing = claimRepository.findByTankerDeliveryId(delivery.getId());
        if (delivery.getCheckStatus() != DeliveryCheckStatus.SHORT) {
            existing.filter(claim -> claim.getStatus() == ShortageClaimStatus.RAISED)
                    .ifPresent(claim -> withdraw(claim, delivery, currentUser));
            return existing.filter(claim -> claim.getStatus() != ShortageClaimStatus.RAISED).orElse(null);
        }
        if (existing.isEmpty()) {
            return raise(delivery, tank, currentUser);
        }
        ShortageClaim claim = existing.get();
        BigDecimal litresShort = litresShortOf(delivery);
        if (claim.getStatus() == ShortageClaimStatus.RAISED
                && (litresShort.compareTo(claim.getLitresShort()) != 0
                    || delivery.getCostPricePerUnit().compareTo(claim.getPricePerLitre()) != 0)) {
            BigDecimal oldAmount = claim.getClaimAmount();
            fillFigures(claim, delivery, litresShort);
            claimRepository.save(claim);
            auditService.log(delivery.getPumpId(), AuditAction.SHORTAGE_CLAIM_UPDATED, ENTITY_NAME, claim.getId().toString(),
                    "Shortage claim for invoice " + delivery.getInvoiceReference() + " updated after the delivery was corrected: "
                            + claim.getLitresShort() + " L short, ₹" + oldAmount + " → ₹" + claim.getClaimAmount(),
                    currentUser);
            log.info("Shortage claim updated: pump={}, claim={}, delivery={}, litresShort={}, amount={}",
                    delivery.getPumpId(), claim.getId(), delivery.getId(), claim.getLitresShort(), claim.getClaimAmount());
        }
        return claim;
    }

    /** A settled or rejected claim fixes the delivery's figures; they can no longer be corrected or deleted. */
    @Transactional(readOnly = true)
    public void assertDeliveryChangeable(TankerDelivery delivery) {
        claimRepository.findByTankerDeliveryId(delivery.getId())
                .filter(claim -> claim.getStatus() != ShortageClaimStatus.RAISED)
                .ifPresent(claim -> {
                    throw new BusinessException(ErrorCodes.SHORTAGE_CLAIM_CLOSED_FOR_DELIVERY,
                            Map.of("invoiceReference", String.valueOf(delivery.getInvoiceReference()),
                                   "statusCode", claim.getStatus().name()),
                            "The shortage claim for invoice '" + delivery.getInvoiceReference() + "' is "
                                    + claim.getStatus() + ", so this delivery's quantity and cost can no longer change.");
                });
    }

    /**
     * Records the supplier's credit note. The lot's cost per litre drops to what was finally paid for the litres
     * received, and fuel already used from it is re-costed.
     */
    @Transactional
    public ShortageClaimResponse settle(Long pumpId, Long claimId, SettleShortageClaimRequest request, User currentUser) {
        ShortageClaim claim = findRaisedClaim(pumpId, claimId);
        if (request.creditNoteDate().isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.CREDIT_NOTE_DATE_IN_FUTURE, Map.of(),
                    "The credit note date cannot be in the future.");
        }
        TankerDelivery delivery = deliveryOf(claim);
        BigDecimal invoiceValue = delivery.getQuantityDelivered().multiply(delivery.getCostPricePerUnit())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal creditNoteAmount = request.creditNoteAmount().setScale(2, RoundingMode.HALF_UP);
        if (creditNoteAmount.compareTo(invoiceValue) >= 0) {
            throw new BusinessException(ErrorCodes.CREDIT_NOTE_EXCEEDS_INVOICE_VALUE,
                    Map.of("creditNoteAmount", creditNoteAmount.toPlainString(), "invoiceValue", invoiceValue.toPlainString()),
                    "A credit note of ₹" + creditNoteAmount + " must be less than the delivery's invoice value of ₹"
                            + invoiceValue + ".");
        }

        claim.setStatus(ShortageClaimStatus.SETTLED);
        claim.setCreditNoteAmount(creditNoteAmount);
        claim.setCreditNoteReference(request.creditNoteReference().trim());
        claim.setCreditNoteDate(request.creditNoteDate());
        markResolved(claim, currentUser);
        claimRepository.save(claim);
        recostLotAfterCredit(delivery, invoiceValue.subtract(creditNoteAmount), currentUser);

        auditService.log(pumpId, AuditAction.SHORTAGE_CLAIM_SETTLED, ENTITY_NAME, claim.getId().toString(),
                "Shortage claim for invoice " + delivery.getInvoiceReference() + " settled: credit note "
                        + claim.getCreditNoteReference() + " dated " + claim.getCreditNoteDate() + " for ₹" + creditNoteAmount
                        + " against ₹" + claim.getClaimAmount() + " claimed",
                currentUser);
        log.info("Shortage claim settled: pump={}, claim={}, delivery={}, creditNote={}, by={}",
                pumpId, claimId, delivery.getId(), creditNoteAmount, currentUser.getId());
        return queryService.getClaim(pumpId, claimId);
    }

    @Transactional
    public ShortageClaimResponse reject(Long pumpId, Long claimId, RejectShortageClaimRequest request, User currentUser) {
        ShortageClaim claim = findRaisedClaim(pumpId, claimId);
        claim.setStatus(ShortageClaimStatus.REJECTED);
        claim.setRejectionReason(request.reason().trim());
        markResolved(claim, currentUser);
        claimRepository.save(claim);

        auditService.log(pumpId, AuditAction.SHORTAGE_CLAIM_REJECTED, ENTITY_NAME, claim.getId().toString(),
                "Shortage claim of ₹" + claim.getClaimAmount() + " rejected by the supplier: " + claim.getRejectionReason(),
                currentUser);
        log.info("Shortage claim rejected: pump={}, claim={}, by={}", pumpId, claimId, currentUser.getId());
        return queryService.getClaim(pumpId, claimId);
    }

    private ShortageClaim raise(TankerDelivery delivery, UndergroundTank tank, User currentUser) {
        ShortageClaim claim = ShortageClaim.builder()
                .pumpId(delivery.getPumpId())
                .tankerDeliveryId(delivery.getId())
                .raisedByUserId(currentUser.getId())
                .build();
        fillFigures(claim, delivery, litresShortOf(delivery));
        claim = claimRepository.save(claim);

        BigDecimal shortPercent = ReceiptCheckCalculator.percentOfInvoice(claim.getLitresShort(), delivery.getQuantityDelivered());
        String litres = claim.getLitresShort().setScale(2, RoundingMode.HALF_UP).toPlainString();
        auditService.log(delivery.getPumpId(), AuditAction.SHORTAGE_CLAIM_RAISED, ENTITY_NAME, claim.getId().toString(),
                "Shortage claim raised for invoice " + delivery.getInvoiceReference() + ", tank " + tank.getTankIdentifier()
                        + ": received " + delivery.getReceivedLitres() + " L of " + delivery.getQuantityDelivered()
                        + " L, " + litres + " L short (" + shortPercent + "%), ₹" + claim.getClaimAmount(),
                currentUser);
        notifyShortage(delivery, tank, claim, litres, shortPercent);
        log.info("Shortage claim raised: pump={}, claim={}, delivery={}, litresShort={}, amount={}",
                delivery.getPumpId(), claim.getId(), delivery.getId(), claim.getLitresShort(), claim.getClaimAmount());
        return claim;
    }

    private void notifyShortage(TankerDelivery delivery, UndergroundTank tank, ShortageClaim claim, String litres,
                                BigDecimal shortPercent) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tankName", tank.getTankIdentifier());
        params.put("fuelTypeCode", delivery.getFuelType().name());
        params.put("invoiceReference", delivery.getInvoiceReference());
        params.put("shortLitres", litres);
        params.put("shortPercent", shortPercent.toPlainString());
        params.put("claimAmount", claim.getClaimAmount().toPlainString());
        dedupService.maybeInsert(delivery.getPumpId(), NotificationType.DELIVERY_SHORTAGE,
                "DELIVERY_SHORTAGE:delivery:" + delivery.getId(),
                "Short Delivery",
                "Invoice " + delivery.getInvoiceReference() + " to " + tank.getTankIdentifier() + " (" + delivery.getFuelType()
                        + ") was " + litres + " L short (" + shortPercent.toPlainString() + "%). A claim of ₹"
                        + claim.getClaimAmount().toPlainString() + " has been raised.",
                params);
    }

    private void withdraw(ShortageClaim claim, TankerDelivery delivery, User currentUser) {
        claimRepository.delete(claim);
        auditService.log(delivery.getPumpId(), AuditAction.SHORTAGE_CLAIM_WITHDRAWN, ENTITY_NAME, claim.getId().toString(),
                "Shortage claim of ₹" + claim.getClaimAmount() + " for invoice " + delivery.getInvoiceReference()
                        + " withdrawn: the corrected delivery is no longer short beyond tolerance",
                currentUser);
        log.info("Shortage claim withdrawn: pump={}, claim={}, delivery={}", delivery.getPumpId(), claim.getId(), delivery.getId());
    }

    /** Withdraws an open claim together with its delivery; a settled or rejected one stops the deletion. */
    @Transactional
    public void withdrawForDeletedDelivery(TankerDelivery delivery, User currentUser) {
        assertDeliveryChangeable(delivery);
        claimRepository.findByTankerDeliveryId(delivery.getId())
                .ifPresent(claim -> withdraw(claim, delivery, currentUser));
    }

    private void recostLotAfterCredit(TankerDelivery delivery, BigDecimal paidValue, User currentUser) {
        InventoryLot lot = lotRepository.findByTankerDeliveryId(delivery.getId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.STOCK_LOT,
                        "Inventory lot not found for delivery " + delivery.getId()));
        BigDecimal oldCost = lot.getCostPricePerUnit();
        BigDecimal newCost = ReceiptCheckCalculator.costPerReceivedLitre(paidValue, delivery.getReceivedLitres());
        if (newCost.compareTo(oldCost) == 0) {
            return;
        }
        lot.setCostPricePerUnit(newCost);
        lotRepository.save(lot);
        lotCostCorrectionService.recostConsumedFuel(delivery.getPumpId(), lot, oldCost, newCost, delivery, currentUser);
    }

    private ShortageClaim findRaisedClaim(Long pumpId, Long claimId) {
        ShortageClaim claim = claimRepository.findByIdAndPumpId(claimId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHORTAGE_CLAIM, "Shortage claim not found"));
        if (claim.getStatus() != ShortageClaimStatus.RAISED) {
            throw new BusinessException(ErrorCodes.SHORTAGE_CLAIM_NOT_RAISED,
                    Map.of("statusCode", claim.getStatus().name()),
                    "This shortage claim is already " + claim.getStatus() + "; only a raised claim can be settled or rejected.");
        }
        return claim;
    }

    private TankerDelivery deliveryOf(ShortageClaim claim) {
        return deliveryRepository.findById(claim.getTankerDeliveryId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.DELIVERY, "Delivery not found"));
    }

    private void markResolved(ShortageClaim claim, User currentUser) {
        claim.setResolvedByUserId(currentUser.getId());
        claim.setResolvedAt(businessClock.now());
    }

    private static BigDecimal litresShortOf(TankerDelivery delivery) {
        return delivery.getVarianceLitres().negate().setScale(3, RoundingMode.HALF_UP);
    }

    private static void fillFigures(ShortageClaim claim, TankerDelivery delivery, BigDecimal litresShort) {
        claim.setLitresShort(litresShort);
        claim.setPricePerLitre(delivery.getCostPricePerUnit());
        claim.setClaimAmount(litresShort.multiply(delivery.getCostPricePerUnit()).setScale(2, RoundingMode.HALF_UP));
    }
}
