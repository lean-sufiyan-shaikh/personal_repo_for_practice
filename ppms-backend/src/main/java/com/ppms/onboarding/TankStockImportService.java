package com.ppms.onboarding;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.inventory.TankOpeningStockService;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Imports the fuel each tank holds on go-live day, at its cost per litre, as the tank's first lot dated the start of
 * today. A tank that already holds stock or has a lot is skipped, so uploading the same file again adds no fuel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TankStockImportService {

    static final String TANK = "tank";
    static final String LITRES = "litres";
    static final String COST_PER_LITRE = "cost_per_litre";

    private static final BigDecimal MIN_LITRES = new BigDecimal("0.001");
    private static final BigDecimal MIN_COST = new BigDecimal("0.001");
    private static final BigDecimal MAX_COST = new BigDecimal("10000");

    private final UndergroundTankRepository tankRepository;
    private final TankOpeningStockService openingStockService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    private record CheckedRow(RowCheck check, UndergroundTank tank, BigDecimal litres, BigDecimal costPerLitre) {
    }

    @Transactional(readOnly = true)
    public ImportReport preview(Long pumpId, TankStockImportRequest request) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_ROWS);
        return ImportReport.of(checkRows(pumpId, request.rows()).stream().map(CheckedRow::check).toList(), false, List.of());
    }

    @Transactional
    public ImportReport importRows(Long pumpId, TankStockImportRequest request, User currentUser) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_ROWS);
        closedDayGuard.requireTodayOpen(pumpId);
        List<Long> tankIds = tankRepository.findByPumpId(pumpId).stream().map(UndergroundTank::getId).toList();
        if (!tankIds.isEmpty()) {
            tankRepository.findAllByIdForUpdate(tankIds);
        }
        List<CheckedRow> rows = checkRows(pumpId, request.rows());
        List<RowCheck> checks = rows.stream().map(CheckedRow::check).toList();
        ImportLimits.requireNoErrors(ImportReport.of(checks, false, List.of()));

        StringBuilder added = new StringBuilder();
        for (CheckedRow row : rows) {
            if (!row.check().isCreated()) {
                continue;
            }
            openingStockService.addOpeningStock(row.tank(), row.litres(), row.costPerLitre(),
                    businessClock.startOfDay(businessClock.today()));
            added.append(added.isEmpty() ? "" : "; ").append(row.tank().getTankIdentifier()).append(' ')
                    .append(row.litres().toPlainString()).append(" L at ₹").append(row.costPerLitre().toPlainString()).append("/L");
        }

        ImportReport report = ImportReport.of(checks, true, List.of());
        auditService.log(pumpId, AuditAction.TANK_STOCK_IMPORTED, "UndergroundTank", null,
                "Opening tank stock: " + (added.isEmpty() ? "none added" : added) + "; "
                        + report.skipCount() + " tank(s) skipped as already holding stock",
                currentUser);
        log.info("Opening tank stock imported: pump={}, tanks={}, skipped={}, by={}",
                pumpId, report.createCount(), report.skipCount(), currentUser.getId());
        return report;
    }

    private List<CheckedRow> checkRows(Long pumpId, List<TankStockImportRequest.Row> rows) {
        Map<String, UndergroundTank> tanksByIdentifier = tankRepository.findByPumpId(pumpId).stream()
                .collect(Collectors.toMap(tank -> CellReader.lowerCaseKey(tank.getTankIdentifier()), Function.identity(),
                        (first, second) -> first));
        Map<String, Integer> firstRowByTank = new HashMap<>();
        return rows.stream().map(row -> checkRow(row, tanksByIdentifier, firstRowByTank)).toList();
    }

    private CheckedRow checkRow(TankStockImportRequest.Row row, Map<String, UndergroundTank> tanksByIdentifier,
                                Map<String, Integer> firstRowByTank) {
        RowCheck check = new RowCheck(row.rowNumber());
        String identifier = CellReader.requiredText(check, TANK, row.tank(), 100);
        UndergroundTank tank = identifier == null ? null : tanksByIdentifier.get(CellReader.lowerCaseKey(identifier));
        BigDecimal maxLitres = tank != null ? tank.getCapacity() : null;
        BigDecimal litres = CellReader.decimal(check, LITRES, row.litres(), true, MIN_LITRES, maxLitres, 3);
        BigDecimal costPerLitre = CellReader.decimal(check, COST_PER_LITRE, row.costPerLitre(), true, MIN_COST, MAX_COST, 3);
        if (identifier == null) {
            return new CheckedRow(check, null, litres, costPerLitre);
        }
        if (tank == null) {
            check.error(TANK, ErrorCodes.IMPORT_TANK_NOT_FOUND, Map.of("tankIdentifier", identifier));
            return new CheckedRow(check, null, litres, costPerLitre);
        }
        Integer firstRow = firstRowByTank.putIfAbsent(CellReader.lowerCaseKey(identifier), check.rowNumber());
        if (firstRow != null) {
            check.error(TANK, ErrorCodes.IMPORT_DUPLICATE_IN_FILE, Map.of("firstRow", firstRow));
        } else if (tank.getStatus() != TankStatus.ACTIVE) {
            check.error(TANK, ErrorCodes.IMPORT_TANK_NOT_ACTIVE, Map.of("tankIdentifier", tank.getTankIdentifier()));
        } else if (openingStockService.hasStock(tank)) {
            check.skip(TANK, ErrorCodes.IMPORT_TANK_ALREADY_HAS_STOCK, Map.of("tankIdentifier", tank.getTankIdentifier()));
        }
        return new CheckedRow(check, tank, litres, costPerLitre);
    }
}
