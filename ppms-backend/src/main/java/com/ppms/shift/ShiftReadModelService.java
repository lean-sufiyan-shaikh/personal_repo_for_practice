package com.ppms.shift;

import com.ppms.common.time.BusinessClock;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpShiftDefinition;
import com.ppms.pump.PumpShiftDefinitionRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftReadModelService {

    private final NozzleRepository nozzleRepository;
    private final DispensaryUnitRepository duRepository;
    private final UserRepository userRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final ShiftNozzleRepository shiftNozzleRepository;
    private final PumpShiftDefinitionRepository shiftDefinitionRepository;
    private final BusinessClock businessClock;
    private final ShiftReadingPriceSegmentRepository priceSegmentRepository;
    private final ShiftInvestigationNoteRepository investigationNoteRepository;
    private final ShiftOperatorRepository shiftOperatorRepository;
    private final PumpLocationRepository pumpLocationRepository;

    public ShiftResponse toResponseWithLookups(Shift shift) {
        List<Long> nozzleIds = shiftNozzleRepository.findNozzleIdsByShiftId(shift.getId());
        List<Nozzle> nozzles = nozzleRepository.findAllById(nozzleIds);
        DispensaryUnit du = duRepository.findById(shift.getDuId()).orElse(null);
        User operator = userRepository.findById(shift.getOperatorId()).orElse(null);
        String openedByName = userRepository.findById(shift.getOpenedByUserId())
                .map(User::getFullName)
                .orElse(null);
        List<ShiftFuelReading> readings = fuelReadingRepository.findByShiftId(shift.getId());
        List<ShiftCreditEntry> entries = creditEntryRepository.findByShiftId(shift.getId());
        String investigatedByName = shift.getInvestigationStartedById() == null ? null
                : userRepository.findById(shift.getInvestigationStartedById()).map(User::getFullName).orElse(null);
        return toResponse(shift, du, nozzles, operator, openedByName, investigatedByName, readings, entries,
                computeScheduledEndTime(shift), extrasOf(List.of(shift), readings));
    }

    /** List-page variant of {@link #toResponseWithLookups}: loads every related row for all shifts in one query per table. */
    public List<ShiftResponse> toResponsesWithLookups(List<Shift> shifts) {
        if (shifts.isEmpty()) {
            return List.of();
        }
        List<Long> shiftIds = shifts.stream().map(Shift::getId).toList();

        Map<Long, List<Long>> nozzleIdsByShiftId = shiftNozzleRepository.findByShiftIdIn(shiftIds).stream()
                .collect(Collectors.groupingBy(ShiftNozzle::getShiftId,
                        Collectors.mapping(ShiftNozzle::getNozzleId, Collectors.toList())));
        Map<Long, Nozzle> nozzleById = findAllByIdAsMap(nozzleRepository,
                nozzleIdsByShiftId.values().stream().flatMap(List::stream).collect(Collectors.toSet()), Nozzle::getId);
        Map<Long, DispensaryUnit> duById = findAllByIdAsMap(duRepository,
                shifts.stream().map(Shift::getDuId).filter(Objects::nonNull).collect(Collectors.toSet()), DispensaryUnit::getId);
        Set<Long> userIds = new HashSet<>();
        shifts.forEach(shift -> {
            userIds.add(shift.getOperatorId());
            userIds.add(shift.getOpenedByUserId());
            userIds.add(shift.getInvestigationStartedById());
        });
        userIds.remove(null);
        Map<Long, User> userById = findAllByIdAsMap(userRepository, userIds, User::getId);
        Map<Long, PumpShiftDefinition> definitionById = findAllByIdAsMap(shiftDefinitionRepository,
                shifts.stream().map(Shift::getShiftDefinitionId).filter(Objects::nonNull).collect(Collectors.toSet()),
                PumpShiftDefinition::getId);
        Map<Long, List<ShiftFuelReading>> readingsByShiftId = fuelReadingRepository.findByShiftIdIn(shiftIds).stream()
                .collect(Collectors.groupingBy(ShiftFuelReading::getShiftId));
        Map<Long, List<ShiftCreditEntry>> entriesByShiftId = creditEntryRepository.findByShiftIdIn(shiftIds).stream()
                .collect(Collectors.groupingBy(ShiftCreditEntry::getShiftId));
        ReadingExtras extras = extrasOf(shifts, readingsByShiftId.values().stream().flatMap(List::stream).toList());

        return shifts.stream()
                .map(shift -> {
                    List<Nozzle> nozzles = nozzleIdsByShiftId.getOrDefault(shift.getId(), List.of()).stream()
                            .map(nozzleById::get)
                            .filter(Objects::nonNull)
                            .toList();
                    User openedBy = userById.get(shift.getOpenedByUserId());
                    User investigatedBy = userById.get(shift.getInvestigationStartedById());
                    PumpShiftDefinition definition = definitionById.get(shift.getShiftDefinitionId());
                    return toResponse(shift, duById.get(shift.getDuId()), nozzles, userById.get(shift.getOperatorId()),
                            openedBy != null ? openedBy.getFullName() : null,
                            investigatedBy != null ? investigatedBy.getFullName() : null,
                            readingsByShiftId.getOrDefault(shift.getId(), List.of()),
                            entriesByShiftId.getOrDefault(shift.getId(), List.of()),
                            definition != null ? definition.scheduledEnd(shift.getShiftDate(), businessClock.zone()) : null,
                            extras);
                })
                .toList();
    }

    public ShiftResponse toResponse(Shift shift, DispensaryUnit du, List<Nozzle> nozzles,
                                    User operator, String openedByName,
                                    List<ShiftFuelReading> readings, List<ShiftCreditEntry> entries) {
        return toResponse(shift, du, nozzles, operator, openedByName, null, readings, entries, computeScheduledEndTime(shift),
                extrasOf(List.of(shift), readings));
    }

    /**
     * Price segments by reading, investigation notes, other operators and the pump's blind-count setting by shift, each
     * loaded in one query for all the shifts.
     */
    private record ReadingExtras(Map<Long, List<ShiftReadingPriceSegment>> segmentsByReadingId,
                                 Map<Long, List<ShiftResponse.InvestigationNoteResponse>> notesByShiftId,
                                 Map<Long, List<ShiftResponse.OtherOperator>> otherOperatorsByShiftId,
                                 Map<Long, String> nameByUserId,
                                 Set<Long> blindCountPumpIds) {
    }

    private ReadingExtras extrasOf(List<Shift> shifts, List<ShiftFuelReading> readings) {
        List<Long> shiftIds = shifts.stream().map(Shift::getId).toList();
        Map<Long, List<ShiftReadingPriceSegment>> segments = readings.isEmpty() ? Map.of()
                : priceSegmentRepository.findByShiftFuelReadingIdInOrderByStartsAtAscIdAsc(
                                readings.stream().map(ShiftFuelReading::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ShiftReadingPriceSegment::getShiftFuelReadingId));
        List<ShiftInvestigationNote> notes = investigationNoteRepository.findByShiftIdInOrderByWrittenAtAscIdAsc(shiftIds);
        Map<Long, String> writerNameById = findAllByIdAsMap(userRepository,
                notes.stream().map(ShiftInvestigationNote::getWrittenByUserId).collect(Collectors.toSet()), User::getId)
                .values().stream().collect(Collectors.toMap(User::getId, User::getFullName));
        Map<Long, List<ShiftResponse.InvestigationNoteResponse>> notesByShiftId = notes.stream()
                .collect(Collectors.groupingBy(ShiftInvestigationNote::getShiftId, Collectors.mapping(
                        note -> new ShiftResponse.InvestigationNoteResponse(note.getNote(),
                                writerNameById.get(note.getWrittenByUserId()), note.getWrittenAt()),
                        Collectors.toList())));
        List<ShiftOperator> members = shiftOperatorRepository.findByShiftIdIn(shiftIds);
        Set<Long> nameIds = new HashSet<>();
        members.forEach(member -> nameIds.add(member.getOperatorId()));
        shifts.stream().map(Shift::getBlindCountedByUserId).filter(Objects::nonNull).forEach(nameIds::add);
        Map<Long, String> nameByUserId = findAllByIdAsMap(userRepository, nameIds, User::getId).values().stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        Map<Long, List<ShiftResponse.OtherOperator>> otherOperatorsByShiftId = members.stream()
                .sorted(Comparator.comparing(ShiftOperator::getJoinedAt).thenComparing(ShiftOperator::getId))
                .collect(Collectors.groupingBy(ShiftOperator::getShiftId, Collectors.mapping(
                        member -> new ShiftResponse.OtherOperator(member.getOperatorId(),
                                nameByUserId.get(member.getOperatorId()), member.getRole().name(), member.isActive()),
                        Collectors.toList())));
        Set<Long> blindCountPumpIds = findAllByIdAsMap(pumpLocationRepository,
                shifts.stream().map(Shift::getPumpId).collect(Collectors.toSet()), PumpLocation::getId).values().stream()
                .filter(PumpLocation::isBlindCashCount)
                .map(PumpLocation::getId)
                .collect(Collectors.toSet());
        return new ReadingExtras(segments, notesByShiftId, otherOperatorsByShiftId, nameByUserId, blindCountPumpIds);
    }

    private static <T> Map<Long, T> findAllByIdAsMap(JpaRepository<T, Long> repository, Set<Long> ids,
                                                     Function<T, Long> idOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(ids).stream().collect(Collectors.toMap(idOf, Function.identity()));
    }

    private ShiftResponse toResponse(Shift shift, DispensaryUnit du, List<Nozzle> nozzles,
                                     User operator, String openedByName, String investigatedByName,
                                     List<ShiftFuelReading> readings, List<ShiftCreditEntry> entries,
                                     OffsetDateTime scheduledEndTime, ReadingExtras extras) {

        List<ShiftResponse.NozzleSummary> nozzleSummaries = nozzles.stream()
                .sorted(Comparator.comparing(Nozzle::getNozzleNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(n -> new ShiftResponse.NozzleSummary(n.getId(), n.getNozzleNumber(), n.getFuelType().name()))
                .toList();

        Map<Long, BigDecimal> maxMeterByNozzleId = nozzles.stream()
                .collect(Collectors.toMap(Nozzle::getId, Nozzle::getMaxMeterValue, (first, second) -> first));
        Map<Long, Integer> nozzleNumberById = nozzles.stream()
                .filter(n -> n.getNozzleNumber() != null)
                .collect(Collectors.toMap(Nozzle::getId, Nozzle::getNozzleNumber, (first, second) -> first));
        List<ShiftResponse.FuelReadingResponse> fuelReadingResponses = readings.stream()
                .sorted(Comparator.comparing((ShiftFuelReading r) -> nozzleNumberById.get(r.getNozzleId()),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(r -> new ShiftResponse.FuelReadingResponse(
                        r.getNozzleId(),
                        r.getFuelType(),
                        r.getStartReading(),
                        r.getEndReading(),
                        r.getPriceSnapshot(),
                        r.getUnitsSold(),
                        r.getTestLitres(),
                        r.meterLitres(),
                        maxMeterByNozzleId.get(r.getNozzleId()),
                        r.getSalesAmount(),
                        r.getStockShortfallLitres(),
                        extras.segmentsByReadingId().getOrDefault(r.getId(), List.of()).stream()
                                .map(segment -> new ShiftResponse.PriceSegmentResponse(segment.getStartReading(),
                                        segment.getPricePerUnit(), segment.getStartsAt(), segment.getUnitsSold()))
                                .toList()))
                .toList();

        List<ShiftResponse.CreditEntryResponse> creditEntryResponses = entries.stream()
                .map(entry -> new ShiftResponse.CreditEntryResponse(
                        entry.getId(),
                        entry.getClientId(),
                        entry.getClientName(),
                        entry.getBillNo(),
                        entry.getAmount(),
                        entry.getFuelType(),
                        entry.getDescription(),
                        entry.getVoidStatus(),
                        entry.getVoidReason(),
                        entry.getVehicleRegistration(),
                        entry.getDriverName(),
                        entry.getVehicleId(),
                        entry.getDriverPhone(),
                        entry.getLitres(),
                        entry.getVehicleFlag() != null ? entry.getVehicleFlag().name() : null))
                .toList();

        return ShiftResponse.builder()
                .id(shift.getId())
                .pumpId(shift.getPumpId())
                .duId(shift.getDuId())
                .duNumber(du != null ? du.getDuNumber() : null)
                .duName(du != null ? du.getName() : null)
                .nozzles(nozzleSummaries)
                .operatorId(shift.getOperatorId())
                .operatorName(operator != null ? operator.getFullName() : null)
                .openedByUserName(openedByName)
                .shiftWindow(shift.getShiftName())
                .shiftDate(shift.getShiftDate())
                .actualStartTime(shift.getActualStartTime())
                .actualEndTime(shift.getActualEndTime())
                .closedAt(shift.getClosedAt())
                .closedByUserId(shift.getClosedByUserId())
                .scheduledEndTime(scheduledEndTime)
                .fuelReadings(fuelReadingResponses)
                .totalAmountDue(shift.getTotalAmountDue())
                .cashCollected(shift.getCashCollected())
                .upiCollected(shift.getUpiCollected())
                .cardCollected(shift.getCardCollected())
                .fleetCardCollected(shift.getFleetCardCollected())
                .creditTotal(shift.getCreditTotal())
                .cashDropped(shift.getCashDropped())
                .cashDenominations(shift.getCashDenominations())
                .discrepancyAmount(shift.getDiscrepancyAmount())
                .discrepancyType(shift.getDiscrepancyType() != null ? shift.getDiscrepancyType().name() : null)
                .discrepancyReason(shift.getDiscrepancyReason())
                .discrepancyResolution(shift.getDiscrepancyResolution() != null ? shift.getDiscrepancyResolution().name() : null)
                .discrepancyResolutionNote(shift.getDiscrepancyResolutionNote())
                .investigationNote(shift.getInvestigationNote())
                .investigatedByName(investigatedByName)
                .investigationStartedAt(shift.getInvestigationStartedAt())
                .investigationHistory(extras.notesByShiftId().getOrDefault(shift.getId(), List.of()))
                .cashRecoveryAmount(shift.getCashRecoveryAmount())
                .cashRecoveredAt(shift.getCashRecoveredAt())
                .roundingAdjustment(shift.getRoundingAdjustment())
                .stockShortfall(shift.isStockShortfall())
                .status(shift.getStatus().name())
                .isBackfilled(shift.isBackfilled())
                .creditEntries(creditEntryResponses)
                .otherOperators(extras.otherOperatorsByShiftId().getOrDefault(shift.getId(), List.of()))
                .blindCountRequired(extras.blindCountPumpIds().contains(shift.getPumpId()))
                .blindCount(shift.hasBlindCount() ? new ShiftResponse.BlindCount(shift.getBlindCashCounted(),
                        shift.getBlindCashDenominations(), shift.getBlindUpiCounted(), shift.getBlindCardCounted(),
                        shift.getBlindFleetCardCounted(), shift.getBlindCountedAt(),
                        extras.nameByUserId().get(shift.getBlindCountedByUserId())) : null)
                .build();
    }

    /**
     * Derives the shift's scheduled end time from its linked definition.
     * Returns null if no definition is linked (legacy data) or if the definition no longer exists.
     */
    public OffsetDateTime computeScheduledEndTime(Shift shift) {
        if (shift.getShiftDefinitionId() == null) return null;
        return shiftDefinitionRepository.findById(shift.getShiftDefinitionId())
                .map(def -> def.scheduledEnd(shift.getShiftDate(), businessClock.zone()))
                .orElse(null);
    }
}
