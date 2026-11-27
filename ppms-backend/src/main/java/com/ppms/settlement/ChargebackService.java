package com.ppms.settlement;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Records chargebacks and refunds against card, UPI and fleet card collections. One linked to a settlement cannot take
 * back more than that settlement's net still left after earlier chargebacks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargebackService {

    private final SettlementChargebackRepository chargebackRepository;
    private final PaymentSettlementRepository settlementRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final ClosedDayGuard closedDayGuard;
    private final BalanceSheetStalenessService balanceSheetStalenessService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    /** The pump row is locked so two chargebacks on one settlement cannot both pass the remaining-net check. */
    @Transactional
    public ChargebackResponse record(Long pumpId, RecordChargebackRequest request, User currentUser) {
        if (request.chargebackDate().isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.CHARGEBACK_DATE_IN_FUTURE,
                    Map.of("date", request.chargebackDate().toString()), "A chargeback cannot be dated in the future.");
        }
        closedDayGuard.requireOpen(pumpId, request.chargebackDate());
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        SettlementPaymentType paymentType = request.settlementId() != null
                ? checkedSettlementType(pumpId, request, amount)
                : requireCollectionLink(request);
        String reference = request.reference() == null || request.reference().isBlank() ? null : request.reference().trim();
        if (reference != null && chargebackRepository.existsWithReference(pumpId, reference)) {
            throw new BusinessException(ErrorCodes.CHARGEBACK_REFERENCE_DUPLICATE, Map.of("reference", reference),
                    "A chargeback or refund with reference " + reference + " is already recorded for this pump.");
        }

        SettlementChargeback chargeback = chargebackRepository.save(SettlementChargeback.builder()
                .pumpId(pumpId)
                .kind(request.kind())
                .paymentType(paymentType)
                .chargebackDate(request.chargebackDate())
                .amount(amount)
                .reference(reference)
                .reason(request.reason().trim())
                .settlementId(request.settlementId())
                .collectionDate(request.collectionDate())
                .recordedByUserId(currentUser.getId())
                .build());
        balanceSheetStalenessService.markDaySheetsStale(pumpId, List.of(chargeback.getChargebackDate()),
                StaleReasons.chargebackRecorded(chargeback.getId()));
        auditService.log(pumpId, AuditAction.CHARGEBACK_RECORDED, "SettlementChargeback", chargeback.getId().toString(),
                chargeback.getKind() + " of ₹" + amount + " on " + paymentType + " dated " + chargeback.getChargebackDate()
                        + (chargeback.getSettlementId() != null ? " against settlement #" + chargeback.getSettlementId()
                                : " for collections of " + chargeback.getCollectionDate())
                        + (reference != null ? " (reference " + reference + ")" : "") + ": " + chargeback.getReason(),
                currentUser);
        log.info("Chargeback recorded: pump={}, id={}, kind={}, type={}, amount={}, settlement={}, by={}", pumpId,
                chargeback.getId(), chargeback.getKind(), paymentType, amount, chargeback.getSettlementId(), currentUser.getId());
        return ChargebackResponse.of(chargeback, currentUser.getFullName());
    }

    @Transactional(readOnly = true)
    public PagedResponse<ChargebackResponse> list(Long pumpId, int page, int size) {
        Page<SettlementChargeback> chargebacks =
                chargebackRepository.findByPumpIdOrderByChargebackDateDescIdDesc(pumpId, PageRequests.of(page, size));
        Map<Long, String> names = namesOf(chargebacks.getContent().stream()
                .map(SettlementChargeback::getRecordedByUserId).collect(Collectors.toSet()));
        return PagedResponse.of(chargebacks.map(c -> ChargebackResponse.of(c, names.getOrDefault(c.getRecordedByUserId(), "Unknown"))));
    }

    /** Chargebacks taken against each of the given settlements; settlements without one are left out. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> totalsBySettlement(Collection<Long> settlementIds) {
        if (settlementIds.isEmpty()) {
            return Map.of();
        }
        return chargebackRepository.sumAmountBySettlementIds(settlementIds).stream()
                .collect(Collectors.toMap(SettlementChargebackRepository.SettlementTotal::getSettlementId,
                        total -> total.getAmount().setScale(2, RoundingMode.HALF_UP)));
    }

    private SettlementPaymentType checkedSettlementType(Long pumpId, RecordChargebackRequest request, BigDecimal amount) {
        PaymentSettlement settlement = settlementRepository.findByIdAndPumpId(request.settlementId(), pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SETTLEMENT, "Settlement not found"));
        if (PaymentSettlement.VOIDED.equals(settlement.getVoidStatus())) {
            throw new BusinessException(ErrorCodes.CHARGEBACK_SETTLEMENT_VOIDED, Map.of("settlementId", settlement.getId()),
                    "Settlement #" + settlement.getId() + " is voided; a chargeback cannot be taken against it.");
        }
        if (request.paymentType() != null && request.paymentType() != settlement.getPaymentType()) {
            throw new BusinessException(ErrorCodes.CHARGEBACK_PAYMENT_TYPE_MISMATCH,
                    Map.of("paymentTypeCode", request.paymentType().name(),
                            "settlementPaymentTypeCode", settlement.getPaymentType().name()),
                    "Settlement #" + settlement.getId() + " is a " + settlement.getPaymentType() + " settlement, not "
                            + request.paymentType() + ".");
        }
        BigDecimal remaining = settlement.getNetAmount()
                .subtract(chargebackRepository.sumAmountBySettlementId(settlement.getId()))
                .setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException(ErrorCodes.CHARGEBACK_EXCEEDS_SETTLEMENT,
                    Map.of("amount", amount.toPlainString(), "remaining", remaining.max(BigDecimal.ZERO).toPlainString()),
                    "₹" + amount + " is more than the ₹" + remaining.max(BigDecimal.ZERO) + " left of settlement #"
                            + settlement.getId() + " after earlier chargebacks.");
        }
        return settlement.getPaymentType();
    }

    private static SettlementPaymentType requireCollectionLink(RecordChargebackRequest request) {
        if (request.collectionDate() == null || request.paymentType() == null) {
            throw new BusinessException(ErrorCodes.CHARGEBACK_LINK_REQUIRED, Map.of(),
                    "Pick the settlement the money was taken from, or the payment type and the day it was collected.");
        }
        return request.paymentType();
    }

    private Map<Long, String> namesOf(Set<Long> userIds) {
        return userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
    }
}
