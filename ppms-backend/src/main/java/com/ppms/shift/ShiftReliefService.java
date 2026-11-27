package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.Nozzle;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A relief hands an operator's nozzles to a relief operator for a break and back again, at the meter readings of each
 * moment, so the litres in between are the reliever's. A reliever not yet on the shift joins it for the relief only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftReliefService {

    private static final String ENTITY_NAME = "Shift";

    private final ShiftReliefRepository reliefRepository;
    private final ShiftOperatorRepository shiftOperatorRepository;
    private final ShiftOperatorService shiftOperatorService;
    private final NozzleTurnService nozzleTurnService;
    private final ShiftReadModelService shiftReadModelService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public ShiftResponse startRelief(Long pumpId, Long shiftId, StartReliefRequest request, User currentUser) {
        Shift shift = shiftOperatorService.lockOpenShift(pumpId, shiftId);
        Long relievedId = request.relievedOperatorId();
        shiftOperatorService.requireWorkingUnlessManager(shift, currentUser, ErrorCodes.RELIEF_NOT_OWN_SHIFT,
                "Operators can only start a break on a shift they are working.");
        if (relievedId.equals(request.reliefOperatorId())) {
            throw new BusinessException(ErrorCodes.RELIEF_SAME_OPERATOR, Map.of(), "An operator cannot relieve themselves.");
        }
        if (!shiftOperatorService.isWorking(shift, relievedId)) {
            throw ShiftOperatorService.notOnShift(relievedId);
        }
        reliefRepository.findByShiftIdAndRelievedOperatorIdAndEndedAtIsNull(shiftId, relievedId).ifPresent(active -> {
            throw new BusinessException(ErrorCodes.RELIEF_ALREADY_ACTIVE, Map.of("reliefId", active.getId()),
                    "This operator is already on a break. End it before starting another.");
        });
        shiftOperatorService.rejectWhileInRelief(shift, relievedId);
        shiftOperatorService.rejectWhileInRelief(shift, request.reliefOperatorId());

        List<ShiftNozzleTurn> held = nozzleTurnService.heldNozzles(shift, relievedId, currentUser);
        if (held.isEmpty()) {
            throw new BusinessException(ErrorCodes.RELIEF_OPERATOR_HOLDS_NO_NOZZLES, Map.of(),
                    "This operator works no nozzle now, so there is nothing to hand over for the break.");
        }
        Map<Long, BigDecimal> readingByNozzle = readingsByNozzle(request.readings());
        User reliever = shiftOperatorService.isWorking(shift, request.reliefOperatorId())
                ? userRepository.findById(request.reliefOperatorId())
                        .orElseThrow(() -> ShiftOperatorService.notOnShift(request.reliefOperatorId()))
                : joinAsRelief(shift, request.reliefOperatorId(), currentUser);

        ShiftRelief relief = reliefRepository.save(ShiftRelief.builder()
                .shiftId(shiftId)
                .relievedOperatorId(relievedId)
                .reliefOperatorId(reliever.getId())
                .startedAt(businessClock.now())
                .cashHandedOver(moneyOrNull(request.cashHandedOver()))
                .note(NozzleTurnService.trimmedOrNull(request.note()))
                .startedByUserId(currentUser.getId())
                .build());
        for (ShiftNozzleTurn turn : held) {
            Nozzle nozzle = nozzleTurnService.nozzleOfShift(shiftId, turn.getNozzleId());
            nozzleTurnService.passOn(turn, nozzle, requireReading(readingByNozzle, nozzle), reliever.getId(),
                    NozzleTurnStartReason.RELIEF_START, relief.getId(), null, currentUser);
        }
        auditService.log(pumpId, AuditAction.RELIEF_STARTED, ENTITY_NAME, shiftId.toString(),
                reliever.getFullName() + " relieves operator #" + relievedId + " on shift #" + shiftId
                        + (relief.getCashHandedOver() != null ? ", cash handed over ₹" + relief.getCashHandedOver() : ""),
                currentUser);
        log.info("Relief {} started on shift {}: relieved={}, reliever={}, nozzles={}, by={}", relief.getId(), shiftId,
                relievedId, reliever.getId(), held.size(), currentUser.getId());
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    @Transactional
    public ShiftResponse endRelief(Long pumpId, Long shiftId, Long reliefId, EndReliefRequest request, User currentUser) {
        Shift shift = shiftOperatorService.lockOpenShift(pumpId, shiftId);
        ShiftRelief relief = reliefRepository.findByIdAndShiftId(reliefId, shiftId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT_RELIEF, "Relief not found"));
        shiftOperatorService.requireWorkingUnlessManager(shift, currentUser, ErrorCodes.RELIEF_NOT_OWN_SHIFT,
                "Operators can only end a break on a shift they are working.");
        if (!relief.isActive()) {
            throw new BusinessException(ErrorCodes.RELIEF_NOT_ACTIVE, Map.of(), "This relief has already ended.");
        }
        Map<Long, BigDecimal> readingByNozzle = readingsByNozzle(request.readings());
        OffsetDateTime now = businessClock.now();
        List<ShiftNozzleTurn> covered = nozzleTurnService.heldNozzles(shift, relief.getReliefOperatorId(), currentUser).stream()
                .filter(turn -> relief.getId().equals(turn.getReliefId()))
                .toList();
        for (ShiftNozzleTurn turn : covered) {
            Nozzle nozzle = nozzleTurnService.nozzleOfShift(shiftId, turn.getNozzleId());
            nozzleTurnService.passOn(turn, nozzle, requireReading(readingByNozzle, nozzle), relief.getRelievedOperatorId(),
                    NozzleTurnStartReason.RELIEF_END, null, null, currentUser);
        }
        finish(relief, moneyOrNull(request.cashHandedBack()), request.note(), now, currentUser);
        auditService.log(pumpId, AuditAction.RELIEF_ENDED, ENTITY_NAME, shiftId.toString(),
                "Relief #" + reliefId + " ended: operator #" + relief.getRelievedOperatorId() + " back on shift #" + shiftId
                        + (relief.getCashHandedBack() != null ? ", cash handed back ₹" + relief.getCashHandedBack() : ""),
                currentUser);
        log.info("Relief {} ended on shift {}, nozzles={}, by={}", reliefId, shiftId, covered.size(), currentUser.getId());
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    /** A relief still running when the shift closes ends with it; the reliever keeps the litres to the closing reading. */
    void endActiveReliefsAtClose(Long shiftId, OffsetDateTime closedAt, User closedBy) {
        reliefRepository.findByShiftIdAndEndedAtIsNull(shiftId).forEach(relief -> {
            relief.setEndedAt(closedAt);
            relief.setEndedByUserId(closedBy.getId());
            reliefRepository.save(relief);
            log.info("Relief {} ended by the close of shift {}", relief.getId(), shiftId);
        });
    }

    private void finish(ShiftRelief relief, BigDecimal cashHandedBack, String note, OffsetDateTime now, User currentUser) {
        relief.setEndedAt(now);
        relief.setEndedByUserId(currentUser.getId());
        relief.setCashHandedBack(cashHandedBack);
        String endNote = NozzleTurnService.trimmedOrNull(note);
        if (endNote != null) {
            relief.setNote(relief.getNote() == null ? endNote : relief.getNote() + " / " + endNote);
        }
        reliefRepository.save(relief);
        shiftOperatorRepository.findByShiftIdAndOperatorIdAndLeftAtIsNull(relief.getShiftId(), relief.getReliefOperatorId())
                .filter(member -> member.getRole() == ShiftOperatorRole.RELIEF)
                .ifPresent(member -> {
                    member.setLeftAt(now);
                    shiftOperatorRepository.save(member);
                });
    }

    private User joinAsRelief(Shift shift, Long operatorId, User currentUser) {
        User reliever = shiftOperatorService.findOperatorForPump(operatorId, shift.getPumpId());
        shiftOperatorService.join(shift, reliever, ShiftOperatorRole.RELIEF, currentUser);
        return reliever;
    }

    private static Map<Long, BigDecimal> readingsByNozzle(List<NozzleMeterReading> readings) {
        return readings.stream().collect(Collectors.toMap(NozzleMeterReading::nozzleId, NozzleMeterReading::meterReading,
                (first, second) -> {
                    throw new BusinessException(ErrorCodes.NOZZLE_REPEATED_IN_REQUEST, Map.of(),
                            "Each nozzle can only appear once in the request.");
                }));
    }

    private static BigDecimal requireReading(Map<Long, BigDecimal> readingByNozzle, Nozzle nozzle) {
        BigDecimal reading = readingByNozzle.get(nozzle.getId());
        if (reading == null) {
            throw new BusinessException(ErrorCodes.RELIEF_READING_MISSING, Map.of("nozzleNumber", nozzle.getNozzleNumber()),
                    "Enter the meter reading of nozzle #" + nozzle.getNozzleNumber() + ".");
        }
        return reading;
    }

    private static BigDecimal moneyOrNull(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
