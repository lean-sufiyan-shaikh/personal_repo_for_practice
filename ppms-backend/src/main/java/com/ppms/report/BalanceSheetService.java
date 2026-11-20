package com.ppms.report;

import com.ppms.ancillary.AncillaryLotConsumption;
import com.ppms.ancillary.AncillaryLotConsumptionRepository;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.adjustment.FuelDipEntry;
import com.ppms.adjustment.FuelDipEntryRepository;
import com.ppms.adjustment.NozzleReadingAdjustment;
import com.ppms.adjustment.NozzleReadingAdjustmentRepository;
import com.ppms.inventory.DipCheck;
import com.ppms.inventory.DipCheckRepository;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.inventory.InventoryLot;
import com.ppms.inventory.InventoryLotRepository;
import com.ppms.inventory.LotConsumption;
import com.ppms.inventory.LotConsumptionRepository;
import com.ppms.inventory.TankerDelivery;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.pump.PumpShiftDefinition;
import com.ppms.pump.PumpShiftDefinitionService;
import com.ppms.settlement.PaymentSettlement;
import com.ppms.settlement.PaymentSettlementRepository;
import com.ppms.settlement.SettlementPaymentType;
import com.ppms.shift.*;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.ppms.common.dto.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceSheetService {
    private final BalanceSheetRepository        balanceSheetRepository;
    private final BsFuelLineRepository          bsFuelLineRepository;
    private final BsShiftLineRepository         bsShiftLineRepository;

    private final FuelDipEntryRepository               fuelDipEntryRepository;
    private final DipCheckRepository                   dipCheckRepository;
    private final NozzleReadingAdjustmentRepository    adjustmentRepository;
    private final ShiftRepository           shiftRepository;
    private final ShiftFuelReadingRepository shiftFuelReadingRepository;
    private final LotConsumptionRepository  lotConsumptionRepository;
    private final InventoryLotRepository     inventoryLotRepository;
    private final TankerDeliveryRepository  tankerDeliveryRepository;
    private final CreditPaymentRepository   creditPaymentRepository;
    private final ShiftCreditEntryRepository shiftCreditEntryRepository;
    private final GlobalFuelPriceRepository globalFuelPriceRepository;
    private final UndergroundTankRepository tankRepository;
    private final NozzleRepository              nozzleRepository;
    private final com.ppms.pump.DispensaryUnitRepository dispensaryUnitRepository;
    private final UserRepository            userRepository;
    private final PumpShiftDefinitionService shiftDefinitionService;
    private final BusinessClock             businessClock;
    private final BalanceSheetSupportService supportService;
    private final BalanceSheetDetailAssembler detailAssembler;
    private final PaymentSettlementRepository settlementRepository;
    private final PumpLocationRepository    pumpLocationRepository;

    // ── Generate ──────────────────────────────────────────────────────────────

    @Transactional
    public BalanceSheetDetailResponse generate(Long pumpId, GenerateBalanceSheetRequest req, User generatedBy) {
        validateRequest(req);

        LocalDate reportDate = req.getReportDate();
        boolean isShift = req.getReportType() == BalanceSheetReportType.SHIFT;

        // Live DAY report: reportDate == today → a rolling 24-hour window ending now, so that
        // cross-midnight shifts (e.g. 11pm→9am) are captured. Every DAY query uses the same period.
        // SHIFT reports use the calendar day of reportDate for dip and amendment lookups.
        boolean isLiveDay = !isShift && reportDate.equals(businessClock.today());
        ReportPeriod period = isLiveDay
                ? livePeriod(reportDate, businessClock.now())
                : businessDayPeriod(reportDate);
        OffsetDateTime windowStart = period.start();
        OffsetDateTime windowEnd   = period.end();

        // For SHIFT reports, resolve the definition and use it for duplicate check and querying
        PumpShiftDefinition shiftDef = null;
        if (isShift) {
            shiftDef = shiftDefinitionService.getByIdAndPump(req.getShiftDefinitionId(), pumpId);
        }

        // Serialises generation per pump so two concurrent requests cannot both pass the duplicate check.
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Pump not found: " + pumpId));

        // Guard: prevent accidental duplicate reports unless force flag is set
        if (!req.isForceRegenerate()) {
            if (isShift) {
                final Long defId = shiftDef.getId();
                final String defName = shiftDef.getName();
                balanceSheetRepository.findFirstByPumpIdAndReportDateAndShiftDefinitionId(pumpId, reportDate, defId)
                        .ifPresent(bs -> { throw new BusinessException(
                                "A balance sheet for \"" + defName + "\" on " + reportDate +
                                " already exists (ID " + bs.getId() + "). Delete it first to regenerate."); });
            } else {
                balanceSheetRepository.findFirstByPumpIdAndReportDateAndReportType(pumpId, reportDate, BalanceSheetReportType.DAY)
                        .ifPresent(bs -> { throw new BusinessException(
                                "A day-end balance sheet for " + reportDate + " already exists (ID " + bs.getId() + "). Delete it first to regenerate."); });
            }
        }

        // ── 1. Collect closed shifts ──────────────────────────────────────────
        // Live DAY: rolling 24-hour window ending now (captures cross-midnight shifts).
        // Historical DAY / SHIFT: calendar-date or shift-definition filter.
        List<Shift> shifts = isShift
                ? shiftRepository.findClosedShiftsByDefinition(pumpId, reportDate, shiftDef.getId())
                : isLiveDay
                        ? shiftRepository.findClosedShiftsByEndTimeWindow(pumpId, windowStart, windowEnd)
                        : shiftRepository.findClosedShiftsByDate(pumpId, reportDate);

        if (shifts.isEmpty()) {
            String shiftLabel = isShift ? "\"" + shiftDef.getName() + "\" on " : "";
            String windowHint = isLiveDay
                    ? " (window: last 24 hrs ending now — ensure all shifts for this period are closed)"
                    : "";
            throw new BusinessException("No closed shifts found for " + shiftLabel + reportDate + windowHint + ". Close all shifts before generating a balance sheet.");
        }

        List<Long> shiftIds = shifts.stream().map(Shift::getId).toList();

        // ── 2. Fuel readings — litres sold per fuel type ──────────────────────
        List<ShiftFuelReading> allReadings = shiftFuelReadingRepository.findByShiftIdIn(shiftIds);

        // Group readings by fuel type
        Map<FuelType, BigDecimal> litresByFuel      = new EnumMap<>(FuelType.class);
        // Historical expected revenue per fuel type: sum(unitsSold × priceSnapshot) at close time.
        // Using priceSnapshot (not current price) keeps the per-fuel revenue consistent with
        // totalExpectedRevenue which is derived from shift.totalAmountDue (also snapshot-based).
        Map<FuelType, BigDecimal> expectedRevByFuel = new EnumMap<>(FuelType.class);
        Map<Long, List<ShiftFuelReading>> readingsByShift = allReadings.stream()
                .collect(Collectors.groupingBy(ShiftFuelReading::getShiftId));

        for (ShiftFuelReading r : allReadings) {
            BigDecimal units = r.getUnitsSold() != null ? r.getUnitsSold() : BigDecimal.ZERO;
            litresByFuel.merge(r.getFuelType(), units, BigDecimal::add);
            if (r.getPriceSnapshot() != null && units.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal rev = units.multiply(r.getPriceSnapshot());
                expectedRevByFuel.merge(r.getFuelType(), rev, BigDecimal::add);
            }
        }

        // ── 3. COGS — FIFO lot costs for sold litres ──────────────────────────
        // Batch-fetch all consumptions for all shifts in one query
        List<LotConsumption> consumptions = shiftIds.isEmpty()
                ? List.of()
                : lotConsumptionRepository.findByShiftIdIn(shiftIds);

        // Batch-fetch the lots referenced by these consumptions to get per-fuel-type cost
        List<Long> consumedLotIds = consumptions.stream().map(LotConsumption::getLotId).distinct().toList();
        Map<Long, FuelType> lotFuelTypeById = consumedLotIds.isEmpty()
                ? Map.of()
                : inventoryLotRepository.findAllById(consumedLotIds).stream()
                        .collect(Collectors.toMap(InventoryLot::getId, InventoryLot::getFuelType));

        // COGS per fuel type — exact, based on actual lot cost prices consumed in each shift
        Map<FuelType, BigDecimal> cogsByFuel = new EnumMap<>(FuelType.class);
        BigDecimal totalCogs = BigDecimal.ZERO;
        for (LotConsumption c : consumptions) {
            FuelType ft = lotFuelTypeById.get(c.getLotId());
            if (ft == null) continue; // orphaned consumption — skip
            BigDecimal cost = c.getQuantityConsumed().multiply(c.getCostPricePerUnit());
            cogsByFuel.merge(ft, cost, BigDecimal::add);
            totalCogs = totalCogs.add(cost);
        }
        totalCogs = totalCogs.setScale(2, RoundingMode.HALF_UP);

        // ── 4. Global selling prices ──────────────────────────────────────────
        List<GlobalFuelPrice> prices = globalFuelPriceRepository.findCurrentPricesForPump(pumpId);
        Map<FuelType, BigDecimal> priceMap = prices.stream()
                .collect(Collectors.toMap(GlobalFuelPrice::getFuelType, GlobalFuelPrice::getPricePerUnit));

        // ── 5. Tanker deliveries (DAY reports only) ───────────────────────────
        List<TankerDelivery> deliveries = isShift
                ? Collections.emptyList()
                : tankerDeliveryRepository.findByPumpIdAndDeliveryDateBetween(pumpId, period.start(), period.end());

        // Group deliveries by fuel type
        Map<FuelType, BigDecimal> deliveredLitresByFuel = new EnumMap<>(FuelType.class);
        Map<FuelType, BigDecimal> deliveredCostByFuel   = new EnumMap<>(FuelType.class);
        for (TankerDelivery d : deliveries) {
            deliveredLitresByFuel.merge(d.getFuelType(), d.getQuantityDelivered(), BigDecimal::add);
            BigDecimal cost = d.getQuantityDelivered().multiply(d.getCostPricePerUnit());
            deliveredCostByFuel.merge(d.getFuelType(), cost, BigDecimal::add);
        }

        // ── 6. Credit sold per fuel type ──────────────────────────────────────
        // Batch-fetch all credit entries for all shifts in one query.
        // Voided entries are excluded — they represent cancelled transactions and must
        // not inflate the credit-sold figures on the balance sheet.
        Map<FuelType, BigDecimal> creditByFuel = new EnumMap<>(FuelType.class);
        List<ShiftCreditEntry> allCreditEntries = shiftIds.isEmpty()
                ? List.of()
                : shiftCreditEntryRepository.findByShiftIdIn(shiftIds).stream()
                        .filter(e -> !"VOIDED".equals(e.getVoidStatus()))
                        .toList();
        for (ShiftCreditEntry entry : allCreditEntries) {
            try {
                FuelType ft = FuelType.valueOf(entry.getFuelType());
                BigDecimal amt = entry.getAmount() != null ? entry.getAmount() : BigDecimal.ZERO;
                creditByFuel.merge(ft, amt, BigDecimal::add);
            } catch (IllegalArgumentException ignored) {
                // Unrecognised fuel type — skip
            }
        }

        // ── 7. Credit recovered (DAY reports only) ────────────────────────────
        BigDecimal creditRecovered = BigDecimal.ZERO;
        if (!isShift) {
            creditRecovered = isLiveDay
                    ? creditPaymentRepository.sumApprovedAmountByPumpIdRecordedBetween(pumpId, period.start(), period.end())
                    : creditPaymentRepository.sumAmountByPumpIdAndDate(pumpId, reportDate);
        }

        // ── 7b. Cash recoveries for SHORT discrepancies resolved in the period (DAY only) ─
        BigDecimal totalCashRecovery = BigDecimal.ZERO;
        if (!isShift) {
            totalCashRecovery = supportService.orZero(isLiveDay
                    ? shiftRepository.sumCashRecoveriesBetween(pumpId, period.start(), period.end())
                    : shiftRepository.sumCashRecoveriesOnDate(pumpId, reportDate));
        }

        // ── 8. Tank stock at the start and end of the period ──────────────────
        // Rebuilt from the lot ledger so historical reports show the stock of their own period.
        // SHIFT reports span from the first included shift's start to the last one's end.
        OffsetDateTime stockFrom = isShift
                ? shifts.stream().map(Shift::getActualStartTime).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(period.start())
                : period.start();
        OffsetDateTime stockTo = isShift
                ? shifts.stream().map(Shift::getActualEndTime).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(period.end())
                : period.end();
        Map<FuelType, BigDecimal> openingStockByFuel = stockByFuelTypeAt(pumpId, stockFrom);
        Map<FuelType, BigDecimal> closingStockByFuel = stockByFuelTypeAt(pumpId, stockTo);
        List<UndergroundTank> tanks = tankRepository.findByPumpId(pumpId);

        // ── 9. Dip losses for the report period ──────────────────────────────
        List<FuelDipEntry> dipEntries = dipEntriesFor(pumpId, period);

        // Group by fuel type: litres and monetary loss
        Map<FuelType, BigDecimal> dipLitresByFuel = new EnumMap<>(FuelType.class);
        Map<FuelType, BigDecimal> dipAmountByFuel = new EnumMap<>(FuelType.class);
        for (FuelDipEntry d : dipEntries) {
            try {
                FuelType ft = FuelType.valueOf(d.getFuelType());
                dipLitresByFuel.merge(ft, d.getLitresRemoved(), BigDecimal::add);
                dipAmountByFuel.merge(ft, d.getMonetaryLoss(), BigDecimal::add);
            } catch (IllegalArgumentException ignored) {
                // Unknown fuel type in old dip record — skip
            }
        }

        BigDecimal totalDipLossAmount = dipAmountByFuel.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // ── 10a. DIP check variances for the report period ───────────────────
        // These are discrepancies from dipstick readings (measured vs system stock).
        // Different from FuelDipEntry (physical removal for maintenance) — both are tracked.
        // All checks (WITHIN_TOLERANCE, PENDING_REVIEW, REVIEWED) are included: the
        // variance happened regardless of review status.
        List<DipCheck> dipChecks = dipCheckRepository.findByPumpIdAndCheckedAtBetween(pumpId, period.start(), period.end());

        // Build tankId → fuelType map from already-loaded tanks
        Map<Long, FuelType> tankFuelTypeMap = tanks.stream()
                .collect(Collectors.toMap(UndergroundTank::getId, UndergroundTank::getFuelType,
                        (a, b) -> a));

        // Sum net DIP variance per fuel type (positive = surplus found, negative = shortage)
        Map<FuelType, BigDecimal> dipVarianceByFuel = new EnumMap<>(FuelType.class);
        for (DipCheck dc : dipChecks) {
            FuelType ft = tankFuelTypeMap.get(dc.getTankId());
            if (ft == null) continue;
            BigDecimal variance = dc.getMeasuredQuantity().subtract(dc.getSystemStock());
            dipVarianceByFuel.merge(ft, variance, BigDecimal::add);
        }

        // ── 10b. Meter reading amendments for the report period ───────────────
        List<NozzleReadingAdjustment> amendments =
                adjustmentRepository.findByPumpIdAndCreatedAtBetweenOrderByCreatedAtAsc(
                        pumpId, period.start(), period.end());

        // ── 10c. Build individual Dip P/L entries ─────────────────────────────
        // One entry per FuelDipEntry (maintenance removal) and one per DipCheck (variance reading).
        // These are returned in the response for per-entry drill-down.
        List<DipPlLineResponse> dipPlEntries = supportService.buildDipPlEntries(pumpId, dipEntries, dipChecks, tankFuelTypeMap);

        // ── 10. Build fuel lines ──────────────────────────────────────────────
        // Union of all fuel types that appear in readings, deliveries, tanks, or dips
        Set<FuelType> allFuelTypes = new LinkedHashSet<>();
        allFuelTypes.addAll(litresByFuel.keySet());
        allFuelTypes.addAll(deliveredLitresByFuel.keySet());
        allFuelTypes.addAll(openingStockByFuel.keySet());
        allFuelTypes.addAll(closingStockByFuel.keySet());
        allFuelTypes.addAll(dipLitresByFuel.keySet());

        // For COGS per fuel type: distribute proportionally by litres sold
        BigDecimal totalLitresSold = litresByFuel.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── 11. Totals for parent record ─────────────────────────────────────
        BigDecimal totalCash      = shifts.stream().map(s -> supportService.orZero(s.getCashCollected())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalUpi       = shifts.stream().map(s -> supportService.orZero(s.getUpiCollected())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCard      = shifts.stream().map(s -> supportService.orZero(s.getCardCollected())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFleetCard = shifts.stream().map(s -> supportService.orZero(s.getFleetCardCollected())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit    = shifts.stream().map(s -> supportService.orZero(s.getCreditTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Expected revenue = sum of each shift's totalAmountDue (uses priceSnapshot at close time —
        // historically accurate even when prices change between shift close and BS generation).
        BigDecimal totalExpectedRevenue = shifts.stream()
                .map(s -> supportService.orZero(s.getTotalAmountDue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalDeliveredLitres = deliveredLitresByFuel.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        // cashDiscrepancy = Collected − Expected.
        // Positive (+) → OVER  (operator handed in MORE than expected)
        // Negative (−) → SHORT (operator handed in LESS than expected)
        // Fleet card is cash-equivalent — counts in collected total.
        BigDecimal totalCollected = totalCash.add(totalUpi).add(totalCard).add(totalFleetCard).add(totalCredit);
        BigDecimal cashDiscrepancy = totalCollected.subtract(totalExpectedRevenue)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal grossProfit = totalExpectedRevenue.subtract(totalCogs).setScale(2, RoundingMode.HALF_UP);

        // Totals that feed net profit are stored with the sheet so later price changes or late
        // approvals cannot change a saved report.
        BalanceSheetDetailResponse.ProductSalesSummary productSalesSummary =
                isShift ? null : supportService.buildProductSalesSummary(pumpId, period);
        BalanceSheetDetailResponse.ExpenseSummary expenseSummary =
                isShift ? null : supportService.buildExpenseSummary(pumpId, period);
        BigDecimal dipNetAmount = dipPlEntries.stream()
                .map(DipPlLineResponse::getMonetaryAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // ── 11. Persist parent record ─────────────────────────────────────────
        String periodLabel = buildPeriodLabel(req, pumpId, shiftDef, isLiveDay, windowStart, windowEnd);
        BalanceSheet bs = BalanceSheet.builder()
                .pumpId(pumpId)
                .reportType(req.getReportType())
                .reportDate(reportDate)
                .shiftWindow(shiftDef != null ? shiftDef.getName() : null)
                .shiftDefinitionId(shiftDef != null ? shiftDef.getId() : null)
                .periodLabel(periodLabel)
                .generatedByUserId(generatedBy.getId())
                .generatedAt(businessClock.now())
                .notes(req.getNotes())
                .totalExpectedRevenue(totalExpectedRevenue)
                .totalCashCollected(totalCash)
                .totalUpiCollected(totalUpi)
                .totalCardCollected(totalCard)
                .totalFleetCardCollected(totalFleetCard)
                .totalCreditSold(totalCredit)
                .totalCreditRecovered(creditRecovered)
                .totalCashRecovery(totalCashRecovery.setScale(2, RoundingMode.HALF_UP))
                .cashDiscrepancy(cashDiscrepancy)
                .totalLitresSold(totalLitresSold.setScale(3, RoundingMode.HALF_UP))
                .totalLitresDelivered(totalDeliveredLitres.setScale(3, RoundingMode.HALF_UP))
                .totalCostOfGoods(totalCogs)
                .totalGrossProfit(grossProfit)
                .totalDipLossAmount(totalDipLossAmount)
                .totalDipNetAmount(dipNetAmount)
                .productSalesGrossProfit(productSalesSummary != null
                        ? supportService.orZero(productSalesSummary.getGrossProfit()) : BigDecimal.ZERO)
                .totalExpensesAmount(expenseSummary != null
                        ? supportService.orZero(expenseSummary.getTotalAmount()) : BigDecimal.ZERO)
                .build();

        bs = balanceSheetRepository.save(bs);
        final Long bsId = bs.getId();

        // ── 12. Persist fuel lines ────────────────────────────────────────────
        List<BsFuelLine> fuelLines = new ArrayList<>();
        for (FuelType ft : allFuelTypes) {
            BigDecimal soldLitres    = litresByFuel.getOrDefault(ft, BigDecimal.ZERO);
            // sellingPrice is retained for display/reference only (current price at generation time).
            BigDecimal sellingPrice  = priceMap.getOrDefault(ft, BigDecimal.ZERO);
            // expectedRev uses historical price snapshots from each reading so the per-fuel total
            // matches totalExpectedRevenue (sum of shift.totalAmountDue) exactly.
            BigDecimal expectedRev   = expectedRevByFuel.getOrDefault(ft, BigDecimal.ZERO)
                                           .setScale(2, RoundingMode.HALF_UP);
            BigDecimal delivLitres   = deliveredLitresByFuel.getOrDefault(ft, BigDecimal.ZERO);
            BigDecimal delivCost     = deliveredCostByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal creditSold    = creditByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

            // Exact COGS per fuel type from actual FIFO lot costs
            BigDecimal cogsFuel = cogsByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

            BigDecimal grossProfitFuel = expectedRev.subtract(cogsFuel);

            BigDecimal openingStock   = openingStockByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
            BigDecimal closingStock   = closingStockByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
            BigDecimal dipLossLitres  = dipLitresByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
            BigDecimal dipLossAmount  = dipAmountByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            // stockVariance = net DIP check variance from dipstick readings (measured − system_stock).
            // Positive = surplus (more fuel than expected), negative = shortage.
            // Distinct from dipLossLitres which tracks physically removed fuel (maintenance/testing).
            BigDecimal stockVariance  = dipVarianceByFuel.getOrDefault(ft, BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);

            BsFuelLine line = BsFuelLine.builder()
                    .balanceSheetId(bsId)
                    .fuelType(ft.name())
                    .openingStock(openingStock)
                    .closingStock(closingStock)
                    .deliveredLitres(delivLitres.setScale(3, RoundingMode.HALF_UP))
                    .deliveredCost(delivCost)
                    .soldLitres(soldLitres.setScale(3, RoundingMode.HALF_UP))
                    .sellingPrice(sellingPrice)
                    .expectedRevenue(expectedRev)
                    .costOfGoods(cogsFuel)
                    .grossProfit(grossProfitFuel)
                    .creditSoldAmount(creditSold)
                    .stockVariance(stockVariance)
                    .dipLossLitres(dipLossLitres)
                    .dipLossAmount(dipLossAmount)
                    .build();

            fuelLines.add(line);
        }
        bsFuelLineRepository.saveAll(fuelLines);

        // ── 13. Persist shift lines ───────────────────────────────────────────
        List<BsShiftLine> shiftLines = new ArrayList<>();

        // Batch-fetch operator names and nozzle numbers in two queries to avoid N+1.
        Set<Long> operatorIds = shifts.stream().map(Shift::getOperatorId).collect(Collectors.toSet());
        Map<Long, String> operatorNames = userRepository.findAllById(operatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        Set<Long> duIds = shifts.stream().map(Shift::getDuId).collect(Collectors.toSet());
        List<com.ppms.pump.DispensaryUnit> fetchedDUs = dispensaryUnitRepository.findAllById(duIds);
        Map<Long, Integer> duNumbers = fetchedDUs.stream()
                .collect(Collectors.toMap(com.ppms.pump.DispensaryUnit::getId, com.ppms.pump.DispensaryUnit::getDuNumber));
        Map<Long, String> duNames = fetchedDUs.stream()
                .collect(Collectors.toMap(com.ppms.pump.DispensaryUnit::getId, com.ppms.pump.DispensaryUnit::getName));

        for (Shift s : shifts) {
            List<ShiftFuelReading> readings = readingsByShift.getOrDefault(s.getId(), List.of());

            BigDecimal shiftLitres = readings.stream()
                    .map(r -> r.getUnitsSold() != null ? r.getUnitsSold() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            String fuelTypesSummary = readings.stream()
                    .filter(r -> r.getUnitsSold() != null && r.getUnitsSold().compareTo(BigDecimal.ZERO) > 0)
                    .map(r -> r.getFuelType().name())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(", "));
            if (fuelTypesSummary.isEmpty()) fuelTypesSummary = "—";

            // Expected revenue for this shift: use totalAmountDue (computed at close time using
            // priceSnapshot — historically accurate for balance sheets generated after a price change).
            BigDecimal shiftExpected = supportService.orZero(s.getTotalAmountDue());

            // Signed discrepancy: Collected − Expected.
            // Positive (+) → OVER, Negative (−) → SHORT (matches cashDiscrepancy convention above).
            // Fleet card counts as collected (cash-equivalent).
            BigDecimal shiftCollected = supportService.orZero(s.getCashCollected())
                    .add(supportService.orZero(s.getUpiCollected()))
                    .add(supportService.orZero(s.getCardCollected()))
                    .add(supportService.orZero(s.getFleetCardCollected()))
                    .add(supportService.orZero(s.getCreditTotal()));
            BigDecimal shiftDiscrepancy = shiftCollected.subtract(shiftExpected)
                    .setScale(2, RoundingMode.HALF_UP);

            BsShiftLine line = BsShiftLine.builder()
                    .balanceSheetId(bsId)
                    .shiftId(s.getId())
                    .operatorName(operatorNames.getOrDefault(s.getOperatorId(), "Unknown"))
                    .duNumber(duNumbers.getOrDefault(s.getDuId(), 0))
                    .duName(duNames.getOrDefault(s.getDuId(), ""))
                    .fuelTypesSummary(fuelTypesSummary)
                    .litresSold(shiftLitres.setScale(3, RoundingMode.HALF_UP))
                    .expectedRevenue(shiftExpected)
                    .cashCollected(supportService.orZero(s.getCashCollected()))
                    .upiCollected(supportService.orZero(s.getUpiCollected()))
                    .cardCollected(supportService.orZero(s.getCardCollected()))
                    .fleetCardCollected(supportService.orZero(s.getFleetCardCollected()))
                    .creditAmount(supportService.orZero(s.getCreditTotal()))
                    .discrepancy(shiftDiscrepancy)
                    .build();

            shiftLines.add(line);
        }
        bsShiftLineRepository.saveAll(shiftLines);

        // ── 14. Build meter amendment lines ───────────────────────────────────
        // Pre-load user names for amendments in one batch to avoid N+1
        Set<Long> amendmentUserIds = amendments.stream()
                .map(NozzleReadingAdjustment::getRecordedByUserId)
                .collect(Collectors.toSet());
        Map<Long, String> amendmentUserNames = userRepository.findAllById(amendmentUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        List<MeterAmendmentLineResponse> amendmentLines = amendments.stream()
                .map(a -> MeterAmendmentLineResponse.builder()
                        .id(a.getId())
                        .fuelType(a.getFuelType())
                        .adjustmentType(a.getAdjustmentType())
                        .previousReading(a.getPreviousReading())
                        .newReading(a.getNewReading())
                        .delta(a.getNewReading().subtract(a.getPreviousReading()))
                        .reason(a.getReason())
                        .recordedByUserName(amendmentUserNames.getOrDefault(a.getRecordedByUserId(), "Unknown"))
                        .createdAt(a.getCreatedAt())
                        .build())
                .toList();

        log.info("Balance sheet generated: pump={}, type={}, date={}, window={}, shifts={}, dipChecks={}, dipEntries={}, amendments={}, by={}",
                pumpId, req.getReportType(), reportDate, shiftDef != null ? shiftDef.getName() : "DAY",
                shifts.size(), dipChecks.size(), dipEntries.size(), amendmentLines.size(), generatedBy.getId());

        BalanceSheetDetailResponse response = detailAssembler.toDetailResponse(bs, fuelLines, shiftLines,
                amendmentLines, dipPlEntries, generatedBy.getFullName(), productSalesSummary, expenseSummary);

        if (!isShift) {
            response.setSettlementSummary(buildSettlementSummary(pumpId, period));
            List<String> shiftNames = shifts.stream()
                    .map(Shift::getShiftName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            response.setIncludedShiftNames(shiftNames);
        }
        return response;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<BalanceSheetSummaryResponse> list(Long pumpId, LocalDate from, LocalDate to, Pageable pageable) {
        Page<BalanceSheet> sheetsPage = (from != null && to != null)
                ? balanceSheetRepository.findByPumpIdAndReportDateBetweenOrderByReportDateDescGeneratedAtDesc(pumpId, from, to, pageable)
                : balanceSheetRepository.findByPumpIdOrderByReportDateDescGeneratedAtDesc(pumpId, pageable);

        // Batch-load generator names — avoids one DB query per balance sheet in the list
        Set<Long> generatorIds = sheetsPage.getContent().stream()
                .map(BalanceSheet::getGeneratedByUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> userNameMap = userRepository.findAllById(generatorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        // Batch-load shift counts — single query instead of one COUNT per balance sheet row
        Set<Long> bsIds = sheetsPage.getContent().stream()
                .map(BalanceSheet::getId)
                .collect(Collectors.toSet());
        Map<Long, Integer> shiftCountMap = bsIds.isEmpty()
                ? Collections.emptyMap()
                : bsShiftLineRepository.countGroupedByBalanceSheetIdIn(bsIds).stream()
                        .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Long) row[1]).intValue()));

        Page<BalanceSheetSummaryResponse> mapped = sheetsPage.map(bs -> {
            int shiftCount = shiftCountMap.getOrDefault(bs.getId(), 0);
            String userName = bs.getGeneratedByUserId() != null
                    ? userNameMap.getOrDefault(bs.getGeneratedByUserId(), "Unknown")
                    : "System";
            return BalanceSheetSummaryResponse.builder()
                    .id(bs.getId())
                    .reportType(bs.getReportType().name())
                    .reportDate(bs.getReportDate())
                    .shiftWindow(bs.getShiftWindow())
                    .periodLabel(bs.getPeriodLabel())
                    .generatedByUserName(userName)
                    .generatedAt(bs.getGeneratedAt())
                    .shiftCount(shiftCount)
                    .totalLitresSold(bs.getTotalLitresSold())
                    .totalExpectedRevenue(bs.getTotalExpectedRevenue())
                    .totalGrossProfit(bs.getTotalGrossProfit())
                    .cashDiscrepancy(bs.getCashDiscrepancy())
                    .build();
        });

        return PagedResponse.of(mapped);
    }

    @Transactional(readOnly = true)
    public BalanceSheetDetailResponse getById(Long pumpId, Long id) {
        BalanceSheet bs = balanceSheetRepository.findById(id)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new com.ppms.common.exception.ResourceNotFoundException("Balance sheet not found"));
        List<BsFuelLine>  fuelLines  = bsFuelLineRepository.findByBalanceSheetIdOrderByFuelType(id);
        List<BsShiftLine> shiftLines = bsShiftLineRepository.findByBalanceSheetIdOrderByDuNumber(id);
        String userName = bs.getGeneratedByUserId() != null
                ? userRepository.findById(bs.getGeneratedByUserId()).map(User::getFullName).orElse("Unknown")
                : "System";

        // Amendments, dip checks and line items are re-read for the report's period; the totals that
        // feed net profit come from the values stored at generation. A live DAY report re-derives the
        // same 24-hour window anchored to generatedAt.
        LocalDate generatedDate = bs.getGeneratedAt().atZoneSameInstant(businessClock.zone()).toLocalDate();
        boolean isLiveDayReport = bs.getReportType() == BalanceSheetReportType.DAY
                && bs.getReportDate().equals(generatedDate);
        ReportPeriod period = isLiveDayReport
                ? livePeriod(bs.getReportDate(), bs.getGeneratedAt())
                : businessDayPeriod(bs.getReportDate());
        OffsetDateTime from = period.start();
        OffsetDateTime to   = period.end();

        List<NozzleReadingAdjustment> amendments =
                adjustmentRepository.findByPumpIdAndCreatedAtBetweenOrderByCreatedAtAsc(pumpId, from, to);

        Set<Long> userIds = amendments.stream().map(NozzleReadingAdjustment::getRecordedByUserId).collect(Collectors.toSet());
        Map<Long, String> userNames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        List<MeterAmendmentLineResponse> amendmentLines = amendments.stream()
                .map(a -> MeterAmendmentLineResponse.builder()
                        .id(a.getId())
                        .fuelType(a.getFuelType())
                        .adjustmentType(a.getAdjustmentType())
                        .previousReading(a.getPreviousReading())
                        .newReading(a.getNewReading())
                        .delta(a.getNewReading().subtract(a.getPreviousReading()))
                        .reason(a.getReason())
                        .recordedByUserName(userNames.getOrDefault(a.getRecordedByUserId(), "Unknown"))
                        .createdAt(a.getCreatedAt())
                        .build())
                .toList();

        List<FuelDipEntry> dipEntries = dipEntriesFor(pumpId, period);
        List<DipCheck> dipChecks =
                dipCheckRepository.findByPumpIdAndCheckedAtBetween(pumpId, from, to);

        Map<Long, FuelType> tankFuelTypeMap = tankRepository.findByPumpId(pumpId).stream()
                .collect(Collectors.toMap(UndergroundTank::getId, UndergroundTank::getFuelType, (a, b) -> a));

        List<DipPlLineResponse> dipPlEntries = supportService.buildDipPlEntries(pumpId, dipEntries, dipChecks, tankFuelTypeMap);

        boolean isDayReport = bs.getReportType() == BalanceSheetReportType.DAY;
        BalanceSheetDetailResponse.ProductSalesSummary productSalesSummary =
                isDayReport ? supportService.buildProductSalesSummary(bs.getPumpId(), period) : null;
        BalanceSheetDetailResponse.ExpenseSummary expenseSummary =
                isDayReport ? supportService.buildExpenseSummary(bs.getPumpId(), period) : null;

        BalanceSheetDetailResponse response = detailAssembler.toDetailResponse(bs, fuelLines, shiftLines,
                amendmentLines, dipPlEntries, userName, productSalesSummary, expenseSummary);

        if (isDayReport) {
            response.setSettlementSummary(buildSettlementSummary(bs.getPumpId(), period));
            List<Long> shiftIds = shiftLines.stream().map(BsShiftLine::getShiftId).toList();
            List<String> shiftNames = shiftRepository.findAllById(shiftIds).stream()
                    .map(Shift::getShiftName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            response.setIncludedShiftNames(shiftNames);
        }
        return response;
    }

    @Transactional
    public void delete(Long pumpId, Long id) {
        BalanceSheet bs = balanceSheetRepository.findById(id)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new com.ppms.common.exception.ResourceNotFoundException("Balance sheet not found"));
        balanceSheetRepository.delete(bs);
        log.info("Balance sheet deleted: id={}, pump={}", id, pumpId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds the settlement summary for a DAY balance sheet.
     *
     * settlementsOnDate — entries dated on the report date (the date money arrived in bank), or for a
     *                     live report, entries recorded within its 24-hour window.
     * settled amounts   — totals per type for this date.
     * wallet pending    — cumulative (all collections up to reportDate) − (all settlements up to reportDate).
     *
     * The "as-of" wallet snapshot uses reportDate as the cut-off so the figure is historically
     * accurate even when viewed months later.
     */
    private BalanceSheetDetailResponse.SettlementSummary buildSettlementSummary(Long pumpId, ReportPeriod period) {
        LocalDate reportDate = period.reportDate();
        List<PaymentSettlement> onDate = period.live()
                ? settlementRepository.findActiveByPumpIdRecordedBetween(pumpId, period.start(), period.end())
                : settlementRepository.findActiveByPumpIdAndSettlementDate(pumpId, reportDate);

        // Batch-load recorder names
        Set<Long> recorderIds = onDate.stream()
                .map(PaymentSettlement::getRecordedByUserId)
                .collect(Collectors.toSet());
        Map<Long, String> recorderNames = recorderIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(recorderIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getFullName));

        // Per-type settled amounts on this date
        BigDecimal upiOnDate   = onDate.stream()
                .filter(s -> s.getPaymentType() == SettlementPaymentType.UPI)
                .map(PaymentSettlement::getAmountReceived).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cardOnDate  = onDate.stream()
                .filter(s -> s.getPaymentType() == SettlementPaymentType.CARD)
                .map(PaymentSettlement::getAmountReceived).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fleetOnDate = onDate.stream()
                .filter(s -> s.getPaymentType() == SettlementPaymentType.FLEET_CARD)
                .map(PaymentSettlement::getAmountReceived).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Cumulative wallet as-of reportDate
        BigDecimal upiCollected   = shiftRepository.sumUpiCollectedByPumpIdAsOf(pumpId, reportDate);
        BigDecimal cardCollected  = shiftRepository.sumCardCollectedByPumpIdAsOf(pumpId, reportDate);
        BigDecimal fleetCollected = shiftRepository.sumFleetCardCollectedByPumpIdAsOf(pumpId, reportDate);

        BigDecimal upiSettledTotal   = settlementRepository.sumAmountByPumpIdAndPaymentTypeAsOf(pumpId, SettlementPaymentType.UPI, reportDate);
        BigDecimal cardSettledTotal  = settlementRepository.sumAmountByPumpIdAndPaymentTypeAsOf(pumpId, SettlementPaymentType.CARD, reportDate);
        BigDecimal fleetSettledTotal = settlementRepository.sumAmountByPumpIdAndPaymentTypeAsOf(pumpId, SettlementPaymentType.FLEET_CARD, reportDate);

        BigDecimal upiPending   = supportService.orZero(upiCollected).subtract(supportService.orZero(upiSettledTotal)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cardPending  = supportService.orZero(cardCollected).subtract(supportService.orZero(cardSettledTotal)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fleetPending = supportService.orZero(fleetCollected).subtract(supportService.orZero(fleetSettledTotal)).setScale(2, RoundingMode.HALF_UP);

        List<BalanceSheetDetailResponse.SettlementLine> lines = onDate.stream()
                .map(s -> BalanceSheetDetailResponse.SettlementLine.builder()
                        .id(s.getId())
                        .paymentType(s.getPaymentType().name())
                        .amountReceived(s.getAmountReceived())
                        .notes(s.getNotes())
                        .recordedByUserName(recorderNames.getOrDefault(s.getRecordedByUserId(), "Unknown"))
                        .createdAt(s.getCreatedAt())
                        .build())
                .toList();

        return BalanceSheetDetailResponse.SettlementSummary.builder()
                .upiSettledOnDate(upiOnDate.setScale(2, RoundingMode.HALF_UP))
                .cardSettledOnDate(cardOnDate.setScale(2, RoundingMode.HALF_UP))
                .fleetCardSettledOnDate(fleetOnDate.setScale(2, RoundingMode.HALF_UP))
                .walletUpiPending(upiPending)
                .walletCardPending(cardPending)
                .walletFleetCardPending(fleetPending)
                .settlementsOnDate(lines)
                .build();
    }

    private ReportPeriod livePeriod(LocalDate reportDate, OffsetDateTime end) {
        return new ReportPeriod(reportDate, end.minusHours(24), end, true);
    }

    private ReportPeriod businessDayPeriod(LocalDate reportDate) {
        return new ReportPeriod(reportDate, supportService.startOfBusinessDay(reportDate),
                supportService.startOfBusinessDay(reportDate.plusDays(1)), false);
    }

    private List<FuelDipEntry> dipEntriesFor(Long pumpId, ReportPeriod period) {
        return period.live()
                ? fuelDipEntryRepository.findByPumpIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        pumpId, period.start(), period.end())
                : fuelDipEntryRepository.findByPumpIdAndDipDateBetween(pumpId, period.reportDate(), period.reportDate());
    }

    /** Litres held per fuel type at {@code asOf}: everything received before it minus everything consumed by it. */
    private Map<FuelType, BigDecimal> stockByFuelTypeAt(Long pumpId, OffsetDateTime asOf) {
        Map<FuelType, BigDecimal> stock = new EnumMap<>(FuelType.class);
        for (Object[] row : inventoryLotRepository.sumReceivedByFuelTypeBefore(pumpId, asOf)) {
            stock.merge((FuelType) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        for (Object[] row : lotConsumptionRepository.sumConsumedByFuelTypeUpTo(pumpId, asOf)) {
            stock.merge((FuelType) row[0], ((BigDecimal) row[1]).negate(), BigDecimal::add);
        }
        return stock;
    }

    private void validateRequest(GenerateBalanceSheetRequest req) {
        if (req.getReportType() == BalanceSheetReportType.SHIFT) {
            if (req.getShiftDefinitionId() == null) {
                throw new BusinessException("shiftDefinitionId is required for SHIFT reports. Select a shift from the dropdown.");
            }
        }
    }

    private String buildPeriodLabel(GenerateBalanceSheetRequest req, Long pumpId,
                                     com.ppms.pump.PumpShiftDefinition shiftDef,
                                     boolean isLiveDay, OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        String base;
        if (isLiveDay) {
            // Show the actual 24-hour window so the label is unambiguous.
            // Example: "Day End · 21 Apr 15:05 → 22 Apr 15:05"
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM HH:mm");
            String from = windowStart.atZoneSameInstant(businessClock.zone()).format(fmt);
            String to   = windowEnd.atZoneSameInstant(businessClock.zone()).format(fmt);
            base = "Day End · " + from + " → " + to;
        } else {
            base = req.getReportType() == BalanceSheetReportType.DAY
                    ? "Day End · " + req.getReportDate()
                    : shiftDef.getName() + " · " + req.getReportDate();
        }

        if (!req.isForceRegenerate()) return base;

        // Count existing reports for this period to determine the next revision number.
        long existing = req.getReportType() == BalanceSheetReportType.DAY
                ? balanceSheetRepository.countByPumpIdAndReportDateAndReportType(pumpId, req.getReportDate(), BalanceSheetReportType.DAY)
                : balanceSheetRepository.countByPumpIdAndReportDateAndShiftDefinitionId(pumpId, req.getReportDate(), req.getShiftDefinitionId());

        return base + " #" + (existing + 1);
    }

    /**
     * Returns individual Dip P/L entries for a pump over a date range.
     * Used by the Reports page "Dip P/L" tab to show a combined view of
     * maintenance removals (FuelDipEntry) and dipstick variances (DipCheck).
     */
    @Transactional(readOnly = true)
    public List<DipPlLineResponse> getDipPl(Long pumpId, LocalDate from, LocalDate to) {
        List<FuelDipEntry> dipEntries = fuelDipEntryRepository.findByPumpIdAndDipDateBetween(pumpId, from, to);

        OffsetDateTime periodStart = supportService.startOfBusinessDay(from);
        OffsetDateTime periodEnd   = supportService.startOfBusinessDay(to.plusDays(1));
        List<DipCheck> dipChecks = dipCheckRepository.findByPumpIdAndCheckedAtBetween(pumpId, periodStart, periodEnd);

        Map<Long, FuelType> tankFuelTypeMap = tankRepository.findByPumpId(pumpId).stream()
                .collect(Collectors.toMap(UndergroundTank::getId, UndergroundTank::getFuelType, (a, b) -> a));

        return supportService.buildDipPlEntries(pumpId, dipEntries, dipChecks, tankFuelTypeMap);
    }
}
