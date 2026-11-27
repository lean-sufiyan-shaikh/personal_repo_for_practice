package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Splits a closed shift between the operators who worked it. A nozzle's litres sold and amount go to each operator in
 * proportion to the meter litres of their turns; a nozzle without turns belongs to the shift's operator. Rounding
 * differences go to the shift's operator, so the shares always add up to the shift.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftOperatorShareService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ShiftOperatorRepository shiftOperatorRepository;
    private final ShiftNozzleTurnRepository turnRepository;
    private final ShiftOperatorTotalRepository totalRepository;

    /** The operators who worked the shift at any time, the shift's own operator first. */
    Set<Long> workersOf(Shift shift) {
        Set<Long> workers = new LinkedHashSet<>();
        workers.add(shift.getOperatorId());
        shiftOperatorRepository.findByShiftIdOrderByJoinedAtAscIdAsc(shift.getId()).forEach(member -> workers.add(member.getOperatorId()));
        return workers;
    }

    /** Each operator in the split must have worked the shift, once, and every figure must add up to the shift's own. */
    void requireCollectionsAddUp(Shift shift, CloseShiftRequest request, BigDecimal cashDropped) {
        List<CloseShiftRequest.OperatorCollectionRequest> split = request.getOperatorCollections();
        if (split == null || split.isEmpty()) {
            return;
        }
        Set<Long> workers = workersOf(shift);
        Set<Long> seen = new HashSet<>();
        for (CloseShiftRequest.OperatorCollectionRequest line : split) {
            if (!workers.contains(line.operatorId())) {
                throw new BusinessException(ErrorCodes.OPERATOR_COLLECTIONS_NOT_ON_SHIFT, Map.of("operatorId", line.operatorId()),
                        "Operator #" + line.operatorId() + " did not work this shift.");
            }
            if (!seen.add(line.operatorId())) {
                throw new BusinessException(ErrorCodes.OPERATOR_COLLECTIONS_REPEATED, Map.of("operatorId", line.operatorId()),
                        "Operator #" + line.operatorId() + " appears more than once in the split.");
            }
        }
        requireSum(split, CloseShiftRequest.OperatorCollectionRequest::cashCollected, request.getCashCollected().add(cashDropped), "CASH");
        requireSum(split, CloseShiftRequest.OperatorCollectionRequest::upiCollected, request.getUpiCollected(), "UPI");
        requireSum(split, CloseShiftRequest.OperatorCollectionRequest::cardCollected, request.getCardCollected(), "CARD");
        requireSum(split, CloseShiftRequest.OperatorCollectionRequest::fleetCardCollected, request.getFleetCardCollected(), "FLEET_CARD");
        requireSum(split, CloseShiftRequest.OperatorCollectionRequest::creditTotal, request.getCreditTotal(), "CREDIT");
    }

    /** Saves each operator's share at close, with the collections split as asked or, for one operator, the shift's own. */
    void recordAtClose(Shift shift, List<ShiftFuelReading> readings, List<ShiftNozzleTurn> turns,
                       Map<Long, BigDecimal> maxMeterByNozzleId, List<CloseShiftRequest.OperatorCollectionRequest> split) {
        Map<Long, ShiftOperatorTotal> totals = sharesOf(shift, readings, turns, maxMeterByNozzleId);
        Map<Long, CloseShiftRequest.OperatorCollectionRequest> lineByOperator = split == null ? Map.of()
                : split.stream().collect(Collectors.toMap(CloseShiftRequest.OperatorCollectionRequest::operatorId, Function.identity()));
        if (!lineByOperator.isEmpty()) {
            totals.values().forEach(total -> {
                CloseShiftRequest.OperatorCollectionRequest line = lineByOperator.get(total.getOperatorId());
                setCollections(total, line == null ? ZERO : line.cashCollected(), line == null ? ZERO : line.upiCollected(),
                        line == null ? ZERO : line.cardCollected(), line == null ? ZERO : line.fleetCardCollected(),
                        line == null ? ZERO : line.creditTotal());
            });
        } else if (totals.size() == 1) {
            setShiftCollections(totals.values().iterator().next(), shift);
        }
        totalRepository.saveAll(totals.values());
        log.info("Operator shares recorded for shift {}: operators={}, collectionsSplit={}", shift.getId(),
                totals.keySet(), !lineByOperator.isEmpty());
    }

    /**
     * After an amendment the last turn of each nozzle ends at the amended closing reading and the shares are worked
     * out again. A collection split is kept only while it still adds up to the amended collections.
     */
    void recalculateAfterAmendment(Shift shift, List<ShiftFuelReading> readings, Map<Long, BigDecimal> maxMeterByNozzleId) {
        List<ShiftNozzleTurn> turns = turnRepository.findByShiftIdOrderByStartedAtAscIdAsc(shift.getId());
        Map<Long, ShiftNozzleTurn> lastTurnByNozzle = turns.stream().collect(Collectors.toMap(ShiftNozzleTurn::getNozzleId,
                Function.identity(), (earlier, later) -> later));
        for (ShiftFuelReading reading : readings) {
            ShiftNozzleTurn last = lastTurnByNozzle.get(reading.getNozzleId());
            if (last == null) {
                continue;
            }
            if (reading.getEndReading().compareTo(last.getStartReading()) < 0
                    && !MeterRollover.canRollOverFrom(last.getStartReading(), maxMeterByNozzleId.get(reading.getNozzleId()))) {
                throw new BusinessException(ErrorCodes.CLOSING_READING_BELOW_TURN_START,
                        Map.of("closingReading", reading.getEndReading().toPlainString(),
                                "turnStartReading", last.getStartReading().toPlainString(),
                                "fuelTypeCode", reading.getFuelType().name()),
                        "Closing reading " + reading.getEndReading().toPlainString() + " is below the reading "
                                + last.getStartReading().toPlainString() + " taken when the " + reading.getFuelType()
                                + " nozzle last changed hands.");
            }
            last.setEndReading(reading.getEndReading());
        }
        turnRepository.saveAll(lastTurnByNozzle.values());

        Map<Long, ShiftOperatorTotal> previous = totalRepository.findByShiftIdOrderByOperatorIdAsc(shift.getId()).stream()
                .collect(Collectors.toMap(ShiftOperatorTotal::getOperatorId, Function.identity()));
        Map<Long, ShiftOperatorTotal> totals = sharesOf(shift, readings, turns, maxMeterByNozzleId);
        boolean splitStillAddsUp = previous.values().stream().allMatch(total -> total.collected() != null)
                && splitAddsUpTo(previous.values(), shift);
        if (totals.size() == 1) {
            setShiftCollections(totals.values().iterator().next(), shift);
        } else if (splitStillAddsUp) {
            totals.values().forEach(total -> {
                ShiftOperatorTotal old = previous.get(total.getOperatorId());
                if (old != null) {
                    setCollections(total, old.getCashCollected(), old.getUpiCollected(), old.getCardCollected(),
                            old.getFleetCardCollected(), old.getCreditTotal());
                } else {
                    setCollections(total, ZERO, ZERO, ZERO, ZERO, ZERO);
                }
            });
        }
        totalRepository.deleteAll(previous.values());
        totalRepository.flush();
        totalRepository.saveAll(totals.values());
    }

    private Map<Long, ShiftOperatorTotal> sharesOf(Shift shift, List<ShiftFuelReading> readings, List<ShiftNozzleTurn> turns,
                                                   Map<Long, BigDecimal> maxMeterByNozzleId) {
        Map<Long, BigDecimal> litresByOperator = new LinkedHashMap<>();
        Map<Long, BigDecimal> amountByOperator = new LinkedHashMap<>();
        Long owner = shift.getOperatorId();
        litresByOperator.put(owner, ZERO);
        amountByOperator.put(owner, ZERO);
        Map<Long, List<ShiftNozzleTurn>> turnsByNozzle = turns.stream().collect(Collectors.groupingBy(ShiftNozzleTurn::getNozzleId));
        for (ShiftFuelReading reading : readings) {
            BigDecimal unitsSold = orZero(reading.getUnitsSold());
            BigDecimal amount = reading.amountDue();
            List<ShiftNozzleTurn> nozzleTurns = turnsByNozzle.getOrDefault(reading.getNozzleId(), List.of());
            BigDecimal meterLitres = reading.meterLitres() == null ? ZERO : reading.meterLitres();
            if (nozzleTurns.isEmpty() || meterLitres.signum() == 0) {
                litresByOperator.merge(owner, unitsSold, BigDecimal::add);
                amountByOperator.merge(owner, amount, BigDecimal::add);
                continue;
            }
            BigDecimal maxMeter = maxMeterByNozzleId.get(reading.getNozzleId());
            for (ShiftNozzleTurn turn : nozzleTurns) {
                BigDecimal turnLitres = MeterRollover.unitsBetween(turn.getStartReading(), turn.getEndReading(), maxMeter);
                litresByOperator.merge(turn.getOperatorId(), unitsSold.multiply(turnLitres).divide(meterLitres, 6, RoundingMode.HALF_UP),
                        BigDecimal::add);
                amountByOperator.merge(turn.getOperatorId(), amount.multiply(turnLitres).divide(meterLitres, 6, RoundingMode.HALF_UP),
                        BigDecimal::add);
            }
        }
        workersOf(shift).forEach(worker -> {
            litresByOperator.putIfAbsent(worker, ZERO);
            amountByOperator.putIfAbsent(worker, ZERO);
        });

        BigDecimal totalLitres = readings.stream().map(reading -> orZero(reading.getUnitsSold())).reduce(ZERO, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
        BigDecimal totalAmount = orZero(shift.getTotalAmountDue()).setScale(2, RoundingMode.HALF_UP);
        Map<Long, BigDecimal> litres = roundedWithRemainderTo(owner, litresByOperator, totalLitres, 3);
        Map<Long, BigDecimal> amounts = roundedWithRemainderTo(owner, amountByOperator, totalAmount, 2);
        Map<Long, ShiftOperatorTotal> totals = new LinkedHashMap<>();
        litres.keySet().stream().sorted(Comparator.naturalOrder()).forEach(operatorId -> totals.put(operatorId,
                ShiftOperatorTotal.builder()
                        .shiftId(shift.getId())
                        .operatorId(operatorId)
                        .litresSold(litres.get(operatorId).max(ZERO))
                        .amountDue(amounts.get(operatorId).max(ZERO))
                        .build()));
        return totals;
    }

    private static Map<Long, BigDecimal> roundedWithRemainderTo(Long owner, Map<Long, BigDecimal> unrounded, BigDecimal total,
                                                                int scale) {
        Map<Long, BigDecimal> rounded = new LinkedHashMap<>();
        unrounded.forEach((operatorId, value) -> rounded.put(operatorId, value.setScale(scale, RoundingMode.HALF_UP)));
        BigDecimal remainder = total.subtract(rounded.values().stream().reduce(ZERO, BigDecimal::add));
        rounded.merge(owner, remainder, BigDecimal::add);
        return rounded;
    }

    private static boolean splitAddsUpTo(Collection<ShiftOperatorTotal> totals, Shift shift) {
        BigDecimal collected = totals.stream().map(ShiftOperatorTotal::collected).reduce(ZERO, BigDecimal::add);
        BigDecimal shiftCollected = orZero(shift.getCashCollected()).add(orZero(shift.getUpiCollected()))
                .add(orZero(shift.getCardCollected())).add(orZero(shift.getFleetCardCollected())).add(orZero(shift.getCreditTotal()));
        BigDecimal cash = totals.stream().map(ShiftOperatorTotal::getCashCollected).reduce(ZERO, BigDecimal::add);
        return collected.compareTo(shiftCollected) == 0 && cash.compareTo(orZero(shift.getCashCollected())) == 0;
    }

    private static void setShiftCollections(ShiftOperatorTotal total, Shift shift) {
        setCollections(total, orZero(shift.getCashCollected()), orZero(shift.getUpiCollected()), orZero(shift.getCardCollected()),
                orZero(shift.getFleetCardCollected()), orZero(shift.getCreditTotal()));
    }

    private static void setCollections(ShiftOperatorTotal total, BigDecimal cash, BigDecimal upi, BigDecimal card,
                                       BigDecimal fleetCard, BigDecimal credit) {
        total.setCashCollected(money(cash));
        total.setUpiCollected(money(upi));
        total.setCardCollected(money(card));
        total.setFleetCardCollected(money(fleetCard));
        total.setCreditTotal(money(credit));
    }

    private static void requireSum(List<CloseShiftRequest.OperatorCollectionRequest> split,
                                   Function<CloseShiftRequest.OperatorCollectionRequest, BigDecimal> figure,
                                   BigDecimal expected, String collectionCode) {
        BigDecimal sum = money(split.stream().map(figure).reduce(ZERO, BigDecimal::add));
        BigDecimal shiftFigure = money(expected);
        if (sum.compareTo(shiftFigure) != 0) {
            throw new BusinessException(ErrorCodes.OPERATOR_COLLECTIONS_TOTAL_MISMATCH,
                    Map.of("collectionCode", collectionCode, "splitTotal", sum.toPlainString(), "shiftTotal", shiftFigure.toPlainString()),
                    "The " + collectionCode + " split by operator adds up to ₹" + sum + ", not the shift's ₹" + shiftFigure + ".");
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
