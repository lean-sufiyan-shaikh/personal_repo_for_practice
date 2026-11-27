package com.ppms.radar;

import com.ppms.adjustment.NozzleReadingAdjustment;
import com.ppms.adjustment.NozzleReadingAdjustmentRepository;
import com.ppms.cash.CashDropStatus;
import com.ppms.common.time.BusinessClock;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.inventory.WetStockReconciliationService;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.NozzleStatus;
import com.ppms.radar.LeakageRadarResponse.NozzleScore;
import com.ppms.radar.LeakageRadarResponse.PersonScore;
import com.ppms.radar.LeakageRadarResponse.TankScore;
import com.ppms.radar.NozzleScorer.NozzleInfo;
import com.ppms.radar.PersonScorer.Person;
import com.ppms.radar.PersonScorer.ShortageTally;
import com.ppms.shift.ShiftStatus;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the leakage radar for a pump: loads each kind of event for the whole period in one statement, then scores
 * people, nozzles and tanks with the documented rules in PersonScorer, NozzleScorer and TankScorer. Owners are not
 * scored; the radar is theirs to read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeakageRadarService {

    static final Set<ShiftStatus> CLOSED_STATUSES = Arrays.stream(ShiftStatus.values())
            .filter(status -> !status.isOpen())
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(ShiftStatus.class)));
    private static final Set<UserRole> NOT_SCORED_ROLES = EnumSet.of(UserRole.OWNER, UserRole.SUPER_ADMIN);

    private final LeakageRadarRepository radarRepository;
    private final NozzleReadingAdjustmentRepository adjustmentRepository;
    private final DispensaryUnitRepository duRepository;
    private final NozzleRepository nozzleRepository;
    private final UserRepository userRepository;
    private final WetStockReconciliationService reconciliationService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public LeakageRadarResponse radar(Long pumpId, int days) {
        RadarPeriod period = RadarPeriod.endingOn(businessClock.today(), days);
        OffsetDateTime start = businessClock.startOfDay(period.from());
        OffsetDateTime end = businessClock.startOfDay(period.to().plusDays(1));

        List<PersonScore> people = scorePeople(pumpId, period, start, end);
        List<NozzleScore> nozzles = scoreNozzles(pumpId, period, start, end);
        List<TankScore> tanks = reconciliationService.reconcile(pumpId, period.from(), period.to(), null).tanks().stream()
                .map(tank -> TankScorer.score(tank, period.to()))
                .sorted(Comparator.comparingInt(TankScore::score).reversed().thenComparing(TankScore::tankIdentifier))
                .toList();
        log.debug("Leakage radar pump={} days={} people={} nozzles={} tanks={}",
                pumpId, days, people.size(), nozzles.size(), tanks.size());
        return new LeakageRadarResponse(days, period.from(), period.to(), PersonScorer.MINIMUM_SHIFTS,
                people, nozzles, tanks);
    }

    private List<PersonScore> scorePeople(Long pumpId, RadarPeriod period, OffsetDateTime start, OffsetDateTime end) {
        List<ShiftOutcomeRow> shifts = radarRepository.findShiftOutcomes(pumpId, period.from(), period.to(), CLOSED_STATUSES);
        Map<Long, List<ShiftOutcomeRow>> shiftsByOperator = shifts.stream()
                .collect(Collectors.groupingBy(ShiftOutcomeRow::operatorId));
        Map<Long, List<RadarEvidence>> correctionsByUser = correctionsByUser(pumpId, start, end);

        Set<Long> userIds = new LinkedHashSet<>(shiftsByOperator.keySet());
        userIds.addAll(correctionsByUser.keySet());
        ShortageTally wholePump = ShortageTally.of(shifts);
        return userRepository.findAllById(userIds).stream()
                .filter(user -> !NOT_SCORED_ROLES.contains(user.getRole()))
                .map(user -> {
                    List<ShiftOutcomeRow> own = shiftsByOperator.getOrDefault(user.getId(), List.of());
                    return PersonScorer.score(new Person(user.getId(), user.getFullName(), user.getRole().name()), own,
                            wholePump.minus(ShortageTally.of(own)), correctionsByUser.getOrDefault(user.getId(), List.of()),
                            period);
                })
                .sorted(Comparator.comparingInt(PersonScore::score).reversed().thenComparing(PersonScore::name))
                .toList();
    }

    private Map<Long, List<RadarEvidence>> correctionsByUser(Long pumpId, OffsetDateTime start, OffsetDateTime end) {
        Map<Long, List<RadarEvidence>> byUser = new HashMap<>();
        addCorrections(byUser, EvidenceKind.CREDIT_VOID, radarRepository.findCreditVoids(pumpId, start, end));
        addCorrections(byUser, EvidenceKind.CREDIT_REASSIGNED, radarRepository.findCreditReassignments(pumpId, start, end));
        addCorrections(byUser, EvidenceKind.SETTLEMENT_VOID, radarRepository.findSettlementVoids(pumpId, start, end));
        addCorrections(byUser, EvidenceKind.CASH_DROP_REJECTED,
                radarRepository.findCashDropsWithStatus(pumpId, CashDropStatus.REJECTED, start, end));
        addCorrections(byUser, EvidenceKind.EXPENSE_REJECTED,
                radarRepository.findExpensesReviewedAs(pumpId, ExpenseApprovalStatus.REJECTED, start, end));
        return byUser;
    }

    private void addCorrections(Map<Long, List<RadarEvidence>> byUser, EvidenceKind kind, List<CorrectionRow> rows) {
        for (CorrectionRow row : rows) {
            byUser.computeIfAbsent(row.userId(), id -> new ArrayList<>()).add(RadarEvidence.money(kind,
                    row.happenedAt().atZoneSameInstant(businessClock.zone()).toLocalDate(), row.amount(), row.shiftId(),
                    row.referenceId(), row.note()));
        }
    }

    private List<NozzleScore> scoreNozzles(Long pumpId, RadarPeriod period, OffsetDateTime start, OffsetDateTime end) {
        Map<Long, DispensaryUnit> dusById = duRepository.findByPumpIdOrderByDuNumberAsc(pumpId).stream()
                .collect(Collectors.toMap(DispensaryUnit::getId, Function.identity()));
        if (dusById.isEmpty()) {
            return List.of();
        }
        Map<Long, List<NozzleShiftRow>> shiftsByNozzle = radarRepository
                .findNozzleShifts(pumpId, period.from(), period.to(), CLOSED_STATUSES).stream()
                .collect(Collectors.groupingBy(NozzleShiftRow::nozzleId));
        Map<Long, List<NozzleReadingAdjustment>> amendmentsByNozzle = adjustmentRepository
                .findByPumpIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(pumpId, start, end).stream()
                .collect(Collectors.groupingBy(NozzleReadingAdjustment::getNozzleId));

        return nozzleRepository.findByDuIdIn(List.copyOf(dusById.keySet())).stream()
                .filter(nozzle -> nozzle.getStatus() == NozzleStatus.ACTIVE || shiftsByNozzle.containsKey(nozzle.getId())
                        || amendmentsByNozzle.containsKey(nozzle.getId()))
                .map(nozzle -> NozzleScorer.score(nozzleInfo(nozzle, dusById.get(nozzle.getDuId())),
                        shiftsByNozzle.getOrDefault(nozzle.getId(), List.of()),
                        amendmentsByNozzle.getOrDefault(nozzle.getId(), List.of()), businessClock.zone()))
                .sorted(Comparator.comparingInt(NozzleScore::score).reversed()
                        .thenComparing(NozzleScore::duName).thenComparingInt(NozzleScore::nozzleNumber))
                .toList();
    }

    private static NozzleInfo nozzleInfo(Nozzle nozzle, DispensaryUnit du) {
        return new NozzleInfo(nozzle.getId(), nozzle.getNozzleNumber(), du.getName(), nozzle.getFuelType().name(),
                nozzle.getMaxMeterValue());
    }
}
