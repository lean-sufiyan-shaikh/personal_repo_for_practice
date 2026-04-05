package com.ppms.report;

import com.ppms.fuel.FuelType;
import com.ppms.inventory.InventoryLot;
import com.ppms.inventory.InventoryLotRepository;
import com.ppms.inventory.LotConsumption;
import com.ppms.inventory.LotConsumptionRepository;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftFuelReading;
import com.ppms.shift.ShiftFuelReadingRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ShiftRepository shiftRepository;
    private final ShiftFuelReadingRepository fuelReadingRepository;
    private final LotConsumptionRepository lotConsumptionRepository;
    private final InventoryLotRepository inventoryLotRepository;
    private final UserRepository userRepository;

    // ── P&L Report ────────────────────────────────────────────────────────────

    /**
     * Generates a profit-and-loss summary for a pump over the given date range.
     *
     * Revenue  = sum(unitsSold × priceSnapshot) from all closed shift readings.
     * COGS     = sum(quantityConsumed × costPricePerUnit) from lot consumptions for those shifts.
     * Gross P&L = Revenue − COGS.
     *
     * Both are broken down per fuel type for detailed analysis.
     * DIP-adjustment consumptions are included (they represent real stock reduction).
     */
    public ProfitLossReport buildProfitLossReport(Long pumpId, LocalDate from, LocalDate to) {
        List<Shift> shifts = shiftRepository.findClosedShiftsByDateRange(pumpId, from, to);
        List<Long> shiftIds = shifts.stream().map(Shift::getId).toList();

        // Batch-fetch readings and consumptions — avoids N+1
        List<ShiftFuelReading> readings = shiftIds.isEmpty()
                ? List.of()
                : fuelReadingRepository.findByShiftIdIn(shiftIds);

        List<LotConsumption> consumptions = shiftIds.isEmpty()
                ? List.of()
                : lotConsumptionRepository.findByShiftIdIn(shiftIds);

        // Revenue per fuel type
        Map<FuelType, BigDecimal> revenueByFuel = new LinkedHashMap<>();
        for (ShiftFuelReading r : readings) {
            if (r.getUnitsSold() == null) continue;
            BigDecimal revenue = r.getUnitsSold().multiply(r.getPriceSnapshot()).setScale(2, RoundingMode.HALF_UP);
            revenueByFuel.merge(r.getFuelType(), revenue, BigDecimal::add);
        }

        // COGS per fuel type — requires resolving lot → fuelType.
        // Pre-fetch all referenced lots in one batch query instead of one findById per unique lot.
        Set<Long> referencedLotIds = consumptions.stream().map(LotConsumption::getLotId).collect(Collectors.toSet());
        Map<Long, FuelType> fuelTypeByLotId = inventoryLotRepository.findAllById(referencedLotIds).stream()
                .collect(Collectors.toMap(InventoryLot::getId, InventoryLot::getFuelType));

        Map<FuelType, BigDecimal> cogsByFuel = new LinkedHashMap<>();
        for (LotConsumption c : consumptions) {
            FuelType fuelType = fuelTypeByLotId.get(c.getLotId());
            if (fuelType == null) continue;
            BigDecimal cogs = c.getQuantityConsumed().multiply(c.getCostPricePerUnit()).setScale(2, RoundingMode.HALF_UP);
            cogsByFuel.merge(fuelType, cogs, BigDecimal::add);
        }

        // Build per-fuel lines
        List<ProfitLossReport.FuelLine> lines = new ArrayList<>();
        for (FuelType fuelType : FuelType.values()) {
            BigDecimal revenue = revenueByFuel.getOrDefault(fuelType, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal cogs = cogsByFuel.getOrDefault(fuelType, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            if (revenue.compareTo(BigDecimal.ZERO) == 0 && cogs.compareTo(BigDecimal.ZERO) == 0) continue;
            lines.add(new ProfitLossReport.FuelLine(fuelType.name(), revenue, cogs, revenue.subtract(cogs)));
        }

        BigDecimal totalRevenue = lines.stream().map(ProfitLossReport.FuelLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCogs    = lines.stream().map(ProfitLossReport.FuelLine::cogs).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ProfitLossReport(pumpId, from, to, shifts.size(), totalRevenue.setScale(2, RoundingMode.HALF_UP),
                totalCogs.setScale(2, RoundingMode.HALF_UP), totalRevenue.subtract(totalCogs).setScale(2, RoundingMode.HALF_UP), lines);
    }

    // ── Operator Duty Report ──────────────────────────────────────────────────

    /**
     * Lists all shifts for a specific operator on a pump within the date range.
     * Includes a summary of totals across the period.
     */
    public OperatorDutyReport buildOperatorDutyReport(Long pumpId, Long operatorId, LocalDate from, LocalDate to) {
        List<Shift> shifts = shiftRepository.findByPumpIdAndOperatorIdAndDateRange(pumpId, operatorId, from, to);

        String operatorName = userRepository.findById(operatorId)
                .map(User::getFullName)
                .orElse("Unknown");

        List<OperatorDutyReport.ShiftLine> lines = shifts.stream().map(s -> new OperatorDutyReport.ShiftLine(
                s.getId(),
                s.getShiftDate(),
                s.getShiftName(),
                s.getActualStartTime(),
                s.getActualEndTime(),
                s.getTotalAmountDue() != null ? s.getTotalAmountDue() : BigDecimal.ZERO,
                s.getCashCollected() != null ? s.getCashCollected() : BigDecimal.ZERO,
                s.getUpiCollected() != null ? s.getUpiCollected() : BigDecimal.ZERO,
                s.getCardCollected() != null ? s.getCardCollected() : BigDecimal.ZERO,
                s.getCreditTotal() != null ? s.getCreditTotal() : BigDecimal.ZERO,
                s.getDiscrepancyAmount() != null ? s.getDiscrepancyAmount() : BigDecimal.ZERO,
                s.getDiscrepancyType() != null ? s.getDiscrepancyType().name() : null,
                s.getStatus().name()
        )).toList();

        BigDecimal totalDue          = lines.stream().map(OperatorDutyReport.ShiftLine::totalAmountDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDiscrepancy  = lines.stream().map(OperatorDutyReport.ShiftLine::discrepancyAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OperatorDutyReport(pumpId, operatorId, operatorName, from, to,
                lines.size(), totalDue.setScale(2, RoundingMode.HALF_UP),
                totalDiscrepancy.setScale(2, RoundingMode.HALF_UP), lines);
    }

    // ── Operator Discrepancy Report ───────────────────────────────────────────

    /**
     * Aggregates discrepancy shifts for all operators at a pump over the date range.
     * Only includes shifts that have a discrepancy (SHORT or OVER).
     * Grouped by operator for easy review.
     */
    public OperatorDiscrepancyReport buildOperatorDiscrepancyReport(Long pumpId, LocalDate from, LocalDate to) {
        List<Shift> shifts = shiftRepository.findClosedShiftsByDateRange(pumpId, from, to).stream()
                .filter(s -> s.getDiscrepancyType() != null)
                .toList();

        Map<Long, List<Shift>> byOperator = shifts.stream()
                .collect(Collectors.groupingBy(Shift::getOperatorId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, String> nameCache = new LinkedHashMap<>();
        List<OperatorDiscrepancyReport.OperatorSummary> summaries = new ArrayList<>();

        for (Map.Entry<Long, List<Shift>> entry : byOperator.entrySet()) {
            Long opId = entry.getKey();
            String opName = nameCache.computeIfAbsent(opId, id ->
                    userRepository.findById(id).map(User::getFullName).orElse("Unknown"));

            List<Shift> opShifts = entry.getValue();
            BigDecimal totalShort = opShifts.stream()
                    .filter(s -> s.getDiscrepancyType() != null && s.getDiscrepancyType().name().equals("SHORT"))
                    .map(s -> s.getDiscrepancyAmount() != null ? s.getDiscrepancyAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalOver = opShifts.stream()
                    .filter(s -> s.getDiscrepancyType() != null && s.getDiscrepancyType().name().equals("OVER"))
                    .map(s -> s.getDiscrepancyAmount() != null ? s.getDiscrepancyAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long unresolvedCount = opShifts.stream()
                    .filter(s -> s.getStatus() == ShiftStatus.CLOSED_DISCREPANCY_PENDING)
                    .count();

            List<OperatorDiscrepancyReport.DiscrepancyLine> lines = opShifts.stream()
                    .sorted(Comparator.comparing(Shift::getShiftDate))
                    .map(s -> new OperatorDiscrepancyReport.DiscrepancyLine(
                            s.getId(),
                            s.getShiftDate(),
                            s.getShiftName(),
                            s.getDiscrepancyType().name(),
                            s.getDiscrepancyAmount() != null ? s.getDiscrepancyAmount() : BigDecimal.ZERO,
                            s.getDiscrepancyReason(),
                            s.getStatus().name(),
                            s.getDiscrepancyResolution() != null ? s.getDiscrepancyResolution().name() : null
                    )).toList();

            summaries.add(new OperatorDiscrepancyReport.OperatorSummary(
                    opId, opName, opShifts.size(),
                    totalShort.setScale(2, RoundingMode.HALF_UP),
                    totalOver.setScale(2, RoundingMode.HALF_UP),
                    unresolvedCount, lines));
        }

        return new OperatorDiscrepancyReport(pumpId, from, to, shifts.size(), summaries);
    }

    // ── Inventory Lots Report ─────────────────────────────────────────────────

    /**
     * Lists all inventory lots for a tank with their consumption history.
     * Useful for auditing FIFO deductions and verifying cost accounting.
     */
    public InventoryLotsReport buildInventoryLotsReport(Long tankId) {
        List<InventoryLot> lots = inventoryLotRepository.findAllByTankIdOrdered(tankId);

        // Batch-fetch all consumptions for all lots in one query, then group by lotId
        List<Long> lotIds = lots.stream().map(InventoryLot::getId).toList();
        Map<Long, List<LotConsumption>> consumptionsByLot = lotIds.isEmpty()
                ? Map.of()
                : lotConsumptionRepository.findByLotIdIn(lotIds).stream()
                        .collect(Collectors.groupingBy(LotConsumption::getLotId));

        List<InventoryLotsReport.LotLine> lines = lots.stream().map(lot -> {
            List<LotConsumption> consumptions = consumptionsByLot.getOrDefault(lot.getId(), List.of());
            BigDecimal totalConsumed = consumptions.stream()
                    .map(LotConsumption::getQuantityConsumed)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCogs = consumptions.stream()
                    .map(c -> c.getQuantityConsumed().multiply(c.getCostPricePerUnit()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            List<InventoryLotsReport.ConsumptionLine> consumptionLines = consumptions.stream()
                    .map(c -> new InventoryLotsReport.ConsumptionLine(
                            c.getId(), c.getSourceType().name(), c.getShiftId(),
                            c.getQuantityConsumed(), c.getCostPricePerUnit(),
                            c.getQuantityConsumed().multiply(c.getCostPricePerUnit()).setScale(2, RoundingMode.HALF_UP),
                            c.getConsumedAt()))
                    .toList();

            return new InventoryLotsReport.LotLine(
                    lot.getId(), lot.getFuelType().name(), lot.getDeliveryDate(),
                    lot.getOriginalQuantity(), lot.getRemainingQuantity(),
                    totalConsumed.setScale(3, RoundingMode.HALF_UP),
                    lot.getCostPricePerUnit(), totalCogs, lot.getStatus().name(),
                    lot.getIsDipAdjustment(), consumptionLines);
        }).toList();

        return new InventoryLotsReport(tankId, lots.size(), lines);
    }

    // ── Response types ────────────────────────────────────────────────────────

    public record ProfitLossReport(
            Long pumpId,
            LocalDate from,
            LocalDate to,
            int totalShifts,
            BigDecimal totalRevenue,
            BigDecimal totalCogs,
            BigDecimal grossProfit,
            List<FuelLine> byFuelType) {

        public record FuelLine(String fuelType, BigDecimal revenue, BigDecimal cogs, BigDecimal grossProfit) {}
    }

    public record OperatorDutyReport(
            Long pumpId,
            Long operatorId,
            String operatorName,
            LocalDate from,
            LocalDate to,
            int totalShifts,
            BigDecimal totalAmountDue,
            BigDecimal totalDiscrepancy,
            List<ShiftLine> shifts) {

        public record ShiftLine(
                Long shiftId,
                LocalDate shiftDate,
                String shiftWindow,
                java.time.OffsetDateTime actualStartTime,
                java.time.OffsetDateTime actualEndTime,
                BigDecimal totalAmountDue,
                BigDecimal cashCollected,
                BigDecimal upiCollected,
                BigDecimal cardCollected,
                BigDecimal creditTotal,
                BigDecimal discrepancyAmount,
                String discrepancyType,
                String status) {}
    }

    public record OperatorDiscrepancyReport(
            Long pumpId,
            LocalDate from,
            LocalDate to,
            int totalDiscrepancyShifts,
            List<OperatorSummary> operators) {

        public record OperatorSummary(
                Long operatorId,
                String operatorName,
                int discrepancyShiftCount,
                BigDecimal totalShortAmount,
                BigDecimal totalOverAmount,
                long unresolvedCount,
                List<DiscrepancyLine> shifts) {}

        public record DiscrepancyLine(
                Long shiftId,
                LocalDate shiftDate,
                String shiftWindow,
                String discrepancyType,
                BigDecimal discrepancyAmount,
                String discrepancyReason,
                String status,
                String resolution) {}
    }

    public record InventoryLotsReport(
            Long tankId,
            int totalLots,
            List<LotLine> lots) {

        public record LotLine(
                Long lotId,
                String fuelType,
                java.time.OffsetDateTime deliveryDate,
                BigDecimal originalQuantity,
                BigDecimal remainingQuantity,
                BigDecimal totalConsumed,
                BigDecimal costPricePerUnit,
                BigDecimal totalCogsConsumed,
                String status,
                Boolean isDipAdjustment,
                List<ConsumptionLine> consumptions) {}

        public record ConsumptionLine(
                Long id,
                String sourceType,
                Long shiftId,
                BigDecimal quantityConsumed,
                BigDecimal costPricePerUnit,
                BigDecimal totalCost,
                java.time.OffsetDateTime consumedAt) {}
    }
}
