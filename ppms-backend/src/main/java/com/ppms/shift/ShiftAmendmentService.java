package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashEvent;
import com.ppms.cash.CashEventRepository;
import com.ppms.cash.CashEventType;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.settlement.PaymentSettlementRepository;
import com.ppms.settlement.SettlementPaymentType;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Corrects the closing readings and collections of a closed shift, owner or admin only, with a reason. Litres, amount
 * due and discrepancy are worked out again as at close, the shift's FIFO draw is put back and taken again, the safe
 * gets a correcting movement for any change in bag cash, and the figures before and after are kept as history.
 * The pump row is locked first, as a settlement does, so a digital amount cannot fall below what is already settled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftAmendmentService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ShiftRepository shiftRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final ShiftAmendmentRepository amendmentRepository;
    private final NozzleRepository nozzleRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final PaymentSettlementRepository settlementRepository;
    private final CashEventRepository cashEventRepository;
    private final ShiftLifecycleSupportService lifecycleSupportService;
    private final ShiftFuelDeductionService fuelDeductionService;
    private final ShiftTestLitresService testLitresService;
    private final ShiftReadModelService shiftReadModelService;
    private final BalanceSheetStalenessService balanceSheetStalenessService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final BusinessClock businessClock;

    public record AmendmentResponse(Long id, String reason, Map<String, Object> before, Map<String, Object> after,
                                    String amendedByName, OffsetDateTime amendedAt) {}

    @Transactional
    public ShiftResponse amend(Long pumpId, Long shiftId, AmendShiftRequest request, User currentUser) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        Shift shift = shiftRepository.findByIdAndPumpIdForUpdate(shiftId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        requireAmendable(shift);
        closedDayGuard.requireOpen(pumpId, shift.getShiftDate());

        List<ShiftFuelReading> readings = fuelReadingRepository.findByShiftId(shiftId);
        Map<Long, Nozzle> nozzleById = nozzleRepository.findAllById(
                        readings.stream().map(ShiftFuelReading::getNozzleId).toList()).stream()
                .collect(Collectors.toMap(Nozzle::getId, Function.identity()));
        Map<Long, BigDecimal> newEndByNozzle = endReadingsOf(request, readings);
        Map<String, Object> before = figuresOf(shift, readings, nozzleById);
        Map<Long, BigDecimal> oldEndByNozzle = readings.stream()
                .collect(Collectors.toMap(ShiftFuelReading::getNozzleId, ShiftFuelReading::getEndReading));
        Set<Long> changedTestLitres = testLitresChangedNozzles(request, readings);
        Set<Long> changedNozzles = newEndByNozzle.entrySet().stream()
                .filter(entry -> entry.getValue().compareTo(oldEndByNozzle.get(entry.getKey())) != 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        BigDecimal cashDropped = orZero(shift.getCashDropped());
        BigDecimal oldBagCash = orZero(shift.getCashCollected()).subtract(cashDropped);
        BigDecimal newBagCash = request.cashCollected().setScale(2, RoundingMode.HALF_UP);
        if (changedNozzles.isEmpty() && changedTestLitres.isEmpty() && sameAmounts(shift, oldBagCash, request)) {
            throw new BusinessException(ErrorCodes.SHIFT_AMEND_NO_CHANGE, Map.of(),
                    "The amended figures are the same as the shift's current figures.");
        }
        requireNoLaterShift(shift, changedNozzles, nozzleById);
        requireNotBelowSettled(pumpId, SettlementPaymentType.UPI, shift.getUpiCollected(), request.upiCollected());
        requireNotBelowSettled(pumpId, SettlementPaymentType.CARD, shift.getCardCollected(), request.cardCollected());
        requireNotBelowSettled(pumpId, SettlementPaymentType.FLEET_CARD, shift.getFleetCardCollected(), request.fleetCardCollected());

        Map<Long, BigDecimal> oldUnitsByNozzle = readings.stream()
                .collect(Collectors.toMap(ShiftFuelReading::getNozzleId, reading -> orZero(reading.getUnitsSold())));
        ShiftLifecycleSupportService.ClosingSummary summary = lifecycleSupportService.processClosingReadings(pumpId,
                maxMeterByNozzle(nozzleById), closeRequestOf(request, shift), cashDropped, readings);
        if (!changedTestLitres.isEmpty()) {
            testLitresService.requireNoteAndAuditAboveMax(pumpId, shiftId, readings, request.reason(), currentUser);
        }
        if (readings.stream().anyMatch(reading -> reading.getUnitsSold().compareTo(oldUnitsByNozzle.get(reading.getNozzleId())) != 0)) {
            deductAgain(shift, readings);
        }
        applyAmendedFigures(shift, request, cashDropped, summary);
        shift = shiftRepository.save(shift);
        recordCashCorrection(shift, newBagCash.subtract(oldBagCash), currentUser);
        moveLastReadings(changedNozzles, newEndByNozzle, oldEndByNozzle, nozzleById);

        Map<String, Object> after = figuresOf(shift, readings, nozzleById);
        String reason = request.reason().trim();
        ShiftAmendment amendment = amendmentRepository.save(ShiftAmendment.builder()
                .pumpId(pumpId)
                .shiftId(shiftId)
                .reason(reason)
                .beforeFigures(before)
                .afterFigures(after)
                .amendedByUserId(currentUser.getId())
                .amendedAt(businessClock.now())
                .build());
        balanceSheetStalenessService.markSheetsOfShiftStale(pumpId, shift.getShiftDate(), shift.getShiftDefinitionId(),
                StaleReasons.shiftAmended(shiftId));
        auditService.log(pumpId, AuditAction.SHIFT_AMENDED, "Shift", shiftId.toString(),
                "Shift #" + shiftId + " amended: due ₹" + before.get("totalAmountDue") + " → ₹" + after.get("totalAmountDue")
                        + ", collected ₹" + before.get("totalCollected") + " → ₹" + after.get("totalCollected")
                        + ", discrepancy ₹" + before.get("discrepancyAmount") + " → ₹" + after.get("discrepancyAmount")
                        + ". Reason: " + reason,
                currentUser);
        log.info("Shift amended: pump={}, shift={}, amendment={}, changedNozzles={}, status={}, by={}",
                pumpId, shiftId, amendment.getId(), changedNozzles, shift.getStatus(), currentUser.getId());
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    @Transactional(readOnly = true)
    public List<AmendmentResponse> amendmentsOf(Long pumpId, Long shiftId) {
        shiftRepository.findByIdAndPumpId(shiftId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT, "Shift not found"));
        List<ShiftAmendment> amendments = amendmentRepository.findByShiftIdOrderByAmendedAtDescIdDesc(shiftId);
        Map<Long, String> nameById = userRepository.findAllById(
                        amendments.stream().map(ShiftAmendment::getAmendedByUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return amendments.stream()
                .map(amendment -> new AmendmentResponse(amendment.getId(), amendment.getReason(),
                        amendment.getBeforeFigures(), amendment.getAfterFigures(),
                        nameById.getOrDefault(amendment.getAmendedByUserId(), "Unknown"), amendment.getAmendedAt()))
                .toList();
    }

    /** A discrepancy already settled with money (recovered cash or a salary deduction) cannot be moved under it. */
    private static void requireAmendable(Shift shift) {
        if (shift.getStatus().isOpen()) {
            throw new BusinessException(ErrorCodes.SHIFT_AMEND_NOT_CLOSED, Map.of("statusCode", shift.getStatus().name()),
                    "Only a closed shift can be amended; close it instead.");
        }
        DiscrepancyResolution resolution = shift.getDiscrepancyResolution();
        if (shift.getCashRecoveryAmount() != null) {
            throw new BusinessException(ErrorCodes.SHIFT_AMEND_DISCREPANCY_SETTLED,
                    Map.of("resolutionCode", DiscrepancyResolution.CASH_RECOVERY.name()),
                    "Cash was already recovered for this shift's discrepancy, so its figures can no longer be amended.");
        }
        if (resolution == DiscrepancyResolution.SALARY_DEDUCTION) {
            throw new BusinessException(ErrorCodes.SHIFT_AMEND_DISCREPANCY_SETTLED, Map.of("resolutionCode", resolution.name()),
                    "This shift's discrepancy was settled by " + resolution + ", so its figures can no longer be amended.");
        }
    }

    private static Set<Long> testLitresChangedNozzles(AmendShiftRequest request, List<ShiftFuelReading> readings) {
        Map<Long, BigDecimal> oldTestLitresByNozzle = readings.stream()
                .collect(Collectors.toMap(ShiftFuelReading::getNozzleId, ShiftFuelReading::getTestLitres));
        return request.fuelReadings().stream()
                .filter(reading -> reading.testLitres() != null && oldTestLitresByNozzle.containsKey(reading.nozzleId())
                        && reading.testLitres().compareTo(oldTestLitresByNozzle.get(reading.nozzleId())) != 0)
                .map(CloseShiftRequest.OutletEndReadingRequest::nozzleId)
                .collect(Collectors.toSet());
    }

    private static Map<Long, BigDecimal> endReadingsOf(AmendShiftRequest request, List<ShiftFuelReading> readings) {
        requireShiftNozzles(request, readings);
        return request.fuelReadings().stream()
                .collect(Collectors.toMap(CloseShiftRequest.OutletEndReadingRequest::nozzleId,
                        CloseShiftRequest.OutletEndReadingRequest::endReading, (first, second) -> first, LinkedHashMap::new));
    }

    private static void requireShiftNozzles(AmendShiftRequest request, List<ShiftFuelReading> readings) {
        Set<Long> shiftNozzles = readings.stream().map(ShiftFuelReading::getNozzleId).collect(Collectors.toSet());
        request.fuelReadings().stream()
                .map(CloseShiftRequest.OutletEndReadingRequest::nozzleId)
                .filter(nozzleId -> !shiftNozzles.contains(nozzleId))
                .findFirst()
                .ifPresent(nozzleId -> {
                    throw new BusinessException(ErrorCodes.SHIFT_AMEND_NOZZLE_NOT_IN_SHIFT, Map.of("nozzleId", nozzleId),
                            "Nozzle " + nozzleId + " was not part of this shift.");
                });
    }

    private void requireNoLaterShift(Shift shift, Set<Long> changedNozzles, Map<Long, Nozzle> nozzleById) {
        if (changedNozzles.isEmpty()) {
            return;
        }
        List<Long> usedLater = shiftRepository.findNozzlesUsedByLaterShifts(shift.getId(), changedNozzles,
                shift.getActualStartTime());
        if (!usedLater.isEmpty()) {
            List<Integer> nozzleNumbers = usedLater.stream().map(nozzleById::get).filter(Objects::nonNull)
                    .map(Nozzle::getNozzleNumber).sorted().toList();
            throw new BusinessException(ErrorCodes.SHIFT_AMEND_LATER_SHIFT_ON_NOZZLE, Map.of("nozzleNumbers", nozzleNumbers),
                    "A later shift has already started on nozzle(s) " + nozzleNumbers + " from this shift's closing reading, "
                            + "so the reading cannot be changed. Correct the collections only, or record a meter adjustment.");
        }
    }

    /** Settlements clear the pump's collections first in, first out, so only the pump's unsettled total can shrink. */
    private void requireNotBelowSettled(Long pumpId, SettlementPaymentType type, BigDecimal oldAmount, BigDecimal newAmount) {
        BigDecimal decrease = orZero(oldAmount).subtract(newAmount);
        if (decrease.signum() <= 0) {
            return;
        }
        BigDecimal collected = orZero(switch (type) {
            case UPI -> shiftRepository.sumUpiCollectedByPumpId(pumpId);
            case CARD -> shiftRepository.sumCardCollectedByPumpId(pumpId);
            case FLEET_CARD -> shiftRepository.sumFleetCardCollectedByPumpId(pumpId);
        });
        BigDecimal settled = orZero(settlementRepository.sumAmountByPumpIdAndPaymentType(pumpId, type));
        BigDecimal unsettled = collected.subtract(settled);
        if (decrease.compareTo(unsettled) > 0) {
            BigDecimal lowest = orZero(oldAmount).subtract(unsettled.max(ZERO)).setScale(2, RoundingMode.HALF_UP);
            throw new BusinessException(ErrorCodes.SHIFT_AMEND_BELOW_SETTLED,
                    Map.of("paymentTypeCode", type.name(), "settled", settled.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            "lowest", lowest.toPlainString()),
                    type + " of this shift cannot go below ₹" + lowest + ": ₹" + settled.setScale(2, RoundingMode.HALF_UP)
                            + " of the pump's " + type + " collections is already settled. Void the settlement first.");
        }
    }

    private void deductAgain(Shift shift, List<ShiftFuelReading> readings) {
        fuelDeductionService.lockTanksForDeduction(shift.getPumpId(),
                readings.stream().map(ShiftFuelReading::getTankId).filter(Objects::nonNull).toList(),
                readings.stream().filter(reading -> reading.getTankId() == null).map(ShiftFuelReading::getFuelType).toList());
        fuelDeductionService.restoreShiftCloseConsumption(shift.getId());
        for (ShiftFuelReading reading : readings) {
            reading.setStockShortfallLitres(reading.getUnitsSold().signum() > 0
                    ? fuelDeductionService.deductForShiftClose(shift.getId(), shift.getPumpId(), reading.getFuelType(),
                            reading.getTankId(), reading.getUnitsSold(), shift.getActualStartTime())
                    : BigDecimal.ZERO);
        }
        fuelReadingRepository.saveAll(readings);
        shift.setStockShortfall(shift.isStockShortfall()
                || readings.stream().anyMatch(reading -> reading.getStockShortfallLitres().signum() > 0));
    }

    /** A changed discrepancy needs deciding again, so an earlier waiver or investigation no longer applies. */
    private void applyAmendedFigures(Shift shift, AmendShiftRequest request, BigDecimal cashDropped,
                                     ShiftLifecycleSupportService.ClosingSummary summary) {
        BigDecimal newDiscrepancy = summary.getDiscrepancyAmount().signum() > 0 ? summary.getDiscrepancyAmount() : null;
        boolean discrepancyChanged = summary.getDiscrepancyType() != shift.getDiscrepancyType()
                || orZero(newDiscrepancy).compareTo(orZero(shift.getDiscrepancyAmount())) != 0;
        shift.setTotalAmountDue(summary.getTotalDue());
        shift.setRoundingAdjustment(summary.getRoundingAdjustment());
        shift.setCashCollected(request.cashCollected().setScale(2, RoundingMode.HALF_UP).add(cashDropped));
        shift.setCashDenominations(null);
        shift.setUpiCollected(request.upiCollected().setScale(2, RoundingMode.HALF_UP));
        shift.setCardCollected(request.cardCollected().setScale(2, RoundingMode.HALF_UP));
        shift.setFleetCardCollected(request.fleetCardCollected().setScale(2, RoundingMode.HALF_UP));
        if (!discrepancyChanged) {
            return;
        }
        shift.setDiscrepancyAmount(newDiscrepancy);
        shift.setDiscrepancyType(summary.getDiscrepancyType());
        shift.setDiscrepancyResolution(null);
        shift.setDiscrepancyResolutionNote(null);
        shift.setDiscrepancyResolvedById(null);
        shift.setDiscrepancyResolvedAt(null);
        shift.setInvestigationNote(null);
        shift.setInvestigationStartedById(null);
        shift.setInvestigationStartedAt(null);
        shift.setStatus(lifecycleSupportService.escalateStatusIfNeeded(summary.getStatus(), summary.getDiscrepancyAmount(),
                shift.getPumpId()));
    }

    private void recordCashCorrection(Shift shift, BigDecimal bagCashChange, User currentUser) {
        if (bagCashChange.signum() == 0) {
            return;
        }
        cashEventRepository.save(CashEvent.builder()
                .pumpId(shift.getPumpId())
                .eventType(bagCashChange.signum() > 0 ? CashEventType.CASH_IN : CashEventType.CASH_OUT)
                .amount(bagCashChange.abs())
                .description("Correction of the bag cash of Shift #" + shift.getId() + " after it was amended")
                .eventDate(shift.getShiftDate())
                .shiftId(shift.getId())
                .recordedByUserId(currentUser.getId())
                .build());
    }

    /** Only when the nozzle still shows this shift's old closing reading; a meter adjustment since then stays. */
    private void moveLastReadings(Set<Long> changedNozzles, Map<Long, BigDecimal> newEndByNozzle,
                                  Map<Long, BigDecimal> oldEndByNozzle, Map<Long, Nozzle> nozzleById) {
        List<Nozzle> moved = changedNozzles.stream()
                .map(nozzleById::get)
                .filter(Objects::nonNull)
                .filter(nozzle -> nozzle.getLastReading() != null
                        && nozzle.getLastReading().compareTo(oldEndByNozzle.get(nozzle.getId())) == 0)
                .toList();
        moved.forEach(nozzle -> nozzle.setLastReading(newEndByNozzle.get(nozzle.getId())));
        nozzleRepository.saveAll(moved);
    }

    private static CloseShiftRequest closeRequestOf(AmendShiftRequest request, Shift shift) {
        CloseShiftRequest closeRequest = new CloseShiftRequest();
        closeRequest.setFuelReadings(request.fuelReadings());
        closeRequest.setCashCollected(request.cashCollected());
        closeRequest.setUpiCollected(request.upiCollected());
        closeRequest.setCardCollected(request.cardCollected());
        closeRequest.setFleetCardCollected(request.fleetCardCollected());
        closeRequest.setCreditTotal(orZero(shift.getCreditTotal()));
        return closeRequest;
    }

    private static Map<Long, BigDecimal> maxMeterByNozzle(Map<Long, Nozzle> nozzleById) {
        return nozzleById.values().stream().collect(Collectors.toMap(Nozzle::getId, Nozzle::getMaxMeterValue));
    }

    private static boolean sameAmounts(Shift shift, BigDecimal oldBagCash, AmendShiftRequest request) {
        return oldBagCash.compareTo(request.cashCollected()) == 0
                && orZero(shift.getUpiCollected()).compareTo(request.upiCollected()) == 0
                && orZero(shift.getCardCollected()).compareTo(request.cardCollected()) == 0
                && orZero(shift.getFleetCardCollected()).compareTo(request.fleetCardCollected()) == 0;
    }

    private static Map<String, Object> figuresOf(Shift shift, List<ShiftFuelReading> readings, Map<Long, Nozzle> nozzleById) {
        Map<String, Object> endReadings = new LinkedHashMap<>();
        BigDecimal litres = ZERO;
        for (ShiftFuelReading reading : readings) {
            Nozzle nozzle = nozzleById.get(reading.getNozzleId());
            endReadings.put(String.valueOf(nozzle != null ? nozzle.getNozzleNumber() : reading.getNozzleId()),
                    reading.getEndReading().toPlainString());
            litres = litres.add(orZero(reading.getUnitsSold()));
        }
        BigDecimal collected = orZero(shift.getCashCollected()).add(orZero(shift.getUpiCollected()))
                .add(orZero(shift.getCardCollected())).add(orZero(shift.getFleetCardCollected()))
                .add(orZero(shift.getCreditTotal()));
        Map<String, Object> figures = new LinkedHashMap<>();
        figures.put("endReadingsByNozzleNumber", endReadings);
        figures.put("litresSold", litres.setScale(3, RoundingMode.HALF_UP).toPlainString());
        figures.put("totalAmountDue", money(shift.getTotalAmountDue()));
        figures.put("cashCollected", money(shift.getCashCollected()));
        figures.put("upiCollected", money(shift.getUpiCollected()));
        figures.put("cardCollected", money(shift.getCardCollected()));
        figures.put("fleetCardCollected", money(shift.getFleetCardCollected()));
        figures.put("creditTotal", money(shift.getCreditTotal()));
        figures.put("totalCollected", money(collected));
        figures.put("discrepancyAmount", money(shift.getDiscrepancyAmount()));
        figures.put("discrepancyTypeCode", shift.getDiscrepancyType() != null ? shift.getDiscrepancyType().name() : null);
        figures.put("statusCode", shift.getStatus().name());
        return figures;
    }

    private static String money(BigDecimal amount) {
        return orZero(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal orZero(BigDecimal amount) {
        return amount != null ? amount : ZERO;
    }
}
