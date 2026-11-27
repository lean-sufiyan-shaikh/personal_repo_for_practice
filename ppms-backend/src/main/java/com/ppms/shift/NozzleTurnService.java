package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who works each nozzle of a shift. A nozzle with no turns is worked by the shift's operator from its opening reading;
 * the first swap or relief writes that turn down before starting the next one, so the litres between two readings
 * always belong to exactly one operator.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NozzleTurnService {

    private static final String ENTITY_NAME = "Shift";

    private final ShiftNozzleTurnRepository turnRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final NozzleRepository nozzleRepository;
    private final UserRepository userRepository;
    private final ShiftOperatorService shiftOperatorService;
    private final ShiftReadModelService shiftReadModelService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    /** Moves a nozzle to another operator at the meter reading of the moment; someone not yet on the shift joins it. */
    @Transactional
    public ShiftResponse swapNozzle(Long pumpId, Long shiftId, SwapNozzleRequest request, User currentUser) {
        Shift shift = shiftOperatorService.lockOpenShift(pumpId, shiftId);
        Nozzle nozzle = nozzleOfShift(shiftId, request.nozzleId());
        ShiftNozzleTurn current = currentTurn(shift, nozzle.getId(), currentUser);
        if (current.getReliefId() != null) {
            throw new BusinessException(ErrorCodes.NOZZLE_UNDER_RELIEF, Map.of("nozzleNumber", nozzle.getNozzleNumber()),
                    "Nozzle #" + nozzle.getNozzleNumber() + " is being covered during a break. End the relief first.");
        }
        if (current.getOperatorId().equals(request.toOperatorId())) {
            throw new BusinessException(ErrorCodes.NOZZLE_SWAP_SAME_OPERATOR, Map.of("nozzleNumber", nozzle.getNozzleNumber()),
                    "Nozzle #" + nozzle.getNozzleNumber() + " is already worked by this operator.");
        }
        shiftOperatorService.rejectWhileInRelief(shift, request.toOperatorId());
        User toOperator = shiftOperatorService.isWorking(shift, request.toOperatorId())
                ? userRepository.findById(request.toOperatorId()).orElseThrow(() -> ShiftOperatorService.notOnShift(request.toOperatorId()))
                : joinAsAdditional(shift, request.toOperatorId(), currentUser);

        passOn(current, nozzle, request.meterReading(), toOperator.getId(), NozzleTurnStartReason.SWAP, null,
                trimmedOrNull(request.note()), currentUser);
        auditService.log(pumpId, AuditAction.NOZZLE_SWAPPED, ENTITY_NAME, shiftId.toString(),
                "Nozzle #" + nozzle.getNozzleNumber() + " moved from operator #" + current.getOperatorId() + " to "
                        + toOperator.getFullName() + " at meter " + request.meterReading().toPlainString(), currentUser);
        return shiftReadModelService.toResponseWithLookups(shift);
    }

    private User joinAsAdditional(Shift shift, Long operatorId, User currentUser) {
        User operator = shiftOperatorService.findOperatorForPump(operatorId, shift.getPumpId());
        shiftOperatorService.join(shift, operator, ShiftOperatorRole.ADDITIONAL, currentUser);
        return operator;
    }

    /** The open turn of a nozzle, writing down the shift operator's turn from the opening reading when there is none. */
    ShiftNozzleTurn currentTurn(Shift shift, Long nozzleId, User currentUser) {
        Optional<ShiftNozzleTurn> open = turnRepository.findByShiftIdAndEndedAtIsNull(shift.getId()).stream()
                .filter(turn -> turn.getNozzleId().equals(nozzleId))
                .findFirst();
        if (open.isPresent()) {
            return open.get();
        }
        ShiftFuelReading reading = fuelReadingRepository.findByShiftId(shift.getId()).stream()
                .filter(found -> found.getNozzleId().equals(nozzleId))
                .findFirst()
                .orElseThrow(() -> notInShift(nozzleId));
        return turnRepository.save(ShiftNozzleTurn.builder()
                .shiftId(shift.getId())
                .nozzleId(nozzleId)
                .operatorId(shift.getOperatorId())
                .startReading(reading.getStartReading())
                .startedAt(shift.getActualStartTime())
                .startReason(NozzleTurnStartReason.SHIFT_OPEN)
                .recordedByUserId(currentUser.getId())
                .build());
    }

    /** Ends the current turn at the reading and starts the next operator's turn from it. */
    ShiftNozzleTurn passOn(ShiftNozzleTurn current, Nozzle nozzle, BigDecimal reading, Long toOperatorId,
                           NozzleTurnStartReason reason, Long reliefId, String note, User currentUser) {
        requireReadingAfter(current, nozzle, reading);
        OffsetDateTime now = businessClock.now();
        current.setEndReading(reading);
        current.setEndedAt(now);
        turnRepository.saveAndFlush(current);
        ShiftNozzleTurn next = turnRepository.save(ShiftNozzleTurn.builder()
                .shiftId(current.getShiftId())
                .nozzleId(nozzle.getId())
                .operatorId(toOperatorId)
                .startReading(reading)
                .startedAt(now)
                .startReason(reason)
                .reliefId(reliefId)
                .note(note)
                .recordedByUserId(currentUser.getId())
                .build());
        log.info("Nozzle {} of shift {} passed from operator {} to {} at {} ({}), by={}", nozzle.getId(),
                current.getShiftId(), current.getOperatorId(), toOperatorId, reading, reason, currentUser.getId());
        return next;
    }

    /**
     * Ends every open turn at the closing reading. The closing reading must not be below the reading the last turn
     * started from, unless the meter rolled over from the top of its range.
     */
    List<ShiftNozzleTurn> finishTurnsAtClose(Shift shift, List<ShiftFuelReading> readings, Map<Long, BigDecimal> maxMeterByNozzleId,
                                             OffsetDateTime closedAt) {
        List<ShiftNozzleTurn> openTurns = turnRepository.findByShiftIdAndEndedAtIsNull(shift.getId());
        for (ShiftNozzleTurn turn : openTurns) {
            ShiftFuelReading reading = readings.stream()
                    .filter(found -> found.getNozzleId().equals(turn.getNozzleId()))
                    .findFirst()
                    .orElseThrow(() -> notInShift(turn.getNozzleId()));
            BigDecimal maxMeter = maxMeterByNozzleId.get(turn.getNozzleId());
            if (reading.getEndReading().compareTo(turn.getStartReading()) < 0
                    && !MeterRollover.canRollOverFrom(turn.getStartReading(), maxMeter)) {
                throw new BusinessException(ErrorCodes.CLOSING_READING_BELOW_TURN_START,
                        Map.of("closingReading", reading.getEndReading().toPlainString(),
                                "turnStartReading", turn.getStartReading().toPlainString(),
                                "fuelTypeCode", reading.getFuelType().name()),
                        "Closing reading " + reading.getEndReading().toPlainString() + " is below the reading "
                                + turn.getStartReading().toPlainString() + " taken when the " + reading.getFuelType()
                                + " nozzle last changed hands.");
            }
            turn.setEndReading(reading.getEndReading());
            turn.setEndedAt(closedAt);
        }
        turnRepository.saveAll(openTurns);
        return turnRepository.findByShiftIdOrderByStartedAtAscIdAsc(shift.getId());
    }

    /** The open turns of the nozzles an operator works now. */
    List<ShiftNozzleTurn> heldNozzles(Shift shift, Long operatorId, User currentUser) {
        List<ShiftNozzleTurn> held = new ArrayList<>();
        for (Long nozzleId : shiftNozzleIds(shift.getId())) {
            ShiftNozzleTurn turn = currentTurn(shift, nozzleId, currentUser);
            if (turn.getOperatorId().equals(operatorId)) {
                held.add(turn);
            }
        }
        return held;
    }

    Nozzle nozzleOfShift(Long shiftId, Long nozzleId) {
        if (!shiftNozzleIds(shiftId).contains(nozzleId)) {
            throw notInShift(nozzleId);
        }
        return nozzleRepository.findById(nozzleId).orElseThrow(() -> notInShift(nozzleId));
    }

    private List<Long> shiftNozzleIds(Long shiftId) {
        return fuelReadingRepository.findByShiftId(shiftId).stream().map(ShiftFuelReading::getNozzleId).toList();
    }

    private static void requireReadingAfter(ShiftNozzleTurn current, Nozzle nozzle, BigDecimal reading) {
        if (reading.compareTo(nozzle.getMaxMeterValue()) > 0) {
            throw new BusinessException(ErrorCodes.END_READING_ABOVE_METER_MAX,
                    Map.of("endReading", reading.toPlainString(), "maxMeter", nozzle.getMaxMeterValue().toPlainString(),
                            "fuelTypeCode", nozzle.getFuelType().name()),
                    "Reading " + reading.toPlainString() + " exceeds the maximum meter value of "
                            + nozzle.getMaxMeterValue().toPlainString() + " for the " + nozzle.getFuelType() + " nozzle.");
        }
        if (reading.signum() < 0 || (reading.compareTo(current.getStartReading()) < 0
                && !MeterRollover.canRollOverFrom(current.getStartReading(), nozzle.getMaxMeterValue()))) {
            throw new BusinessException(ErrorCodes.NOZZLE_READING_BELOW_LAST,
                    Map.of("nozzleNumber", nozzle.getNozzleNumber(), "reading", reading.toPlainString(),
                            "lastReading", current.getStartReading().toPlainString()),
                    "Reading " + reading.toPlainString() + " for nozzle #" + nozzle.getNozzleNumber()
                            + " is below its last reading " + current.getStartReading().toPlainString() + ".");
        }
    }

    private static BusinessException notInShift(Long nozzleId) {
        return new BusinessException(ErrorCodes.NOZZLE_NOT_IN_SHIFT, Map.of("nozzleId", nozzleId),
                "Nozzle #" + nozzleId + " is not part of this shift.");
    }

    static String trimmedOrNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
