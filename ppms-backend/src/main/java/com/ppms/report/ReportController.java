package com.ppms.report;

import com.ppms.expense.PumpExpense;
import com.ppms.expense.PumpExpenseRepository;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Operational report endpoints for a pump.
 * Access: MANAGER, ADMIN, OWNER — operators are not allowed to see financial reports.
 *
 * Base URL: /api/pumps/{pumpId}/reports
 */
@RestController
@RequestMapping("/api/pumps/{pumpId}/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'OWNER')")
public class ReportController {

    private final ReportService         reportService;
    private final BalanceSheetService   balanceSheetService;
    private final ShiftRepository       shiftRepository;
    private final PumpExpenseRepository expenseRepository;

    /**
     * GET /api/pumps/{pumpId}/reports/profit-loss?from=&to=
     *
     * Returns a gross profit-and-loss breakdown for the pump over the given date range.
     * Revenue is derived from shift fuel readings; COGS from FIFO lot consumptions.
     */
    @GetMapping("/profit-loss")
    public ResponseEntity<ReportService.ProfitLossReport> getProfitLoss(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.buildProfitLossReport(pumpId, from, to));
    }

    /**
     * GET /api/pumps/{pumpId}/reports/operator-duty?operatorId=&from=&to=
     *
     * Lists all shifts for a specific operator within the date range, with totals summary.
     */
    @GetMapping("/operator-duty")
    public ResponseEntity<ReportService.OperatorDutyReport> getOperatorDuty(
            @PathVariable Long pumpId,
            @RequestParam Long operatorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.buildOperatorDutyReport(pumpId, operatorId, from, to));
    }

    /**
     * GET /api/pumps/{pumpId}/reports/operator-discrepancy?from=&to=
     *
     * Aggregates discrepancy (SHORT/OVER) shifts across all operators for the given date range.
     * Grouped by operator with totals. Useful for weekly management review.
     */
    @GetMapping("/operator-discrepancy")
    public ResponseEntity<ReportService.OperatorDiscrepancyReport> getOperatorDiscrepancy(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.buildOperatorDiscrepancyReport(pumpId, from, to));
    }

    /**
     * GET /api/pumps/{pumpId}/reports/inventory-lots?tankId=
     *
     * Returns all inventory lots for a tank with their FIFO consumption history.
     * Used to audit cost accounting and verify that deductions match physical deliveries.
     */
    @GetMapping("/inventory-lots")
    public ResponseEntity<ReportService.InventoryLotsReport> getInventoryLots(
            @PathVariable Long pumpId,
            @RequestParam Long tankId) {
        return ResponseEntity.ok(reportService.buildInventoryLotsReport(tankId));
    }

    /**
     * GET /api/pumps/{pumpId}/reports/dip-pl?from=&to=
     *
     * Returns individual Dip P/L entries for the given date range.
     * Includes both MAINTENANCE_REMOVAL (FuelDipEntry) and DIP_CHECK (dipstick variance) entries.
     * monetaryAmount is signed: negative = loss, positive = gain.
     * Sorted chronologically by recordedAt.
     */
    @GetMapping("/dip-pl")
    public ResponseEntity<List<DipPlLineResponse>> getDipPl(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(balanceSheetService.getDipPl(pumpId, from, to));
    }

    /**
     * GET /api/pumps/{pumpId}/reports/shifts?from=&to=
     *
     * Returns closed shift summaries for the date range as JSON.
     * Same data as the CSV export but in a structured format for on-screen display and PDF print.
     */
    @GetMapping("/shifts")
    public ResponseEntity<List<ShiftReportLine>> getShiftsReport(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<ShiftReportLine> lines = shiftRepository.findClosedShiftsByDateRange(pumpId, from, to)
                .stream()
                .map(s -> new ShiftReportLine(
                        s.getId(),
                        s.getShiftDate(),
                        s.getShiftName(),
                        s.getStatus() != null ? s.getStatus().name() : null,
                        s.getTotalAmountDue(),
                        s.getCashCollected(),
                        s.getUpiCollected(),
                        s.getCardCollected(),
                        s.getCreditTotal(),
                        s.getDiscrepancyAmount(),
                        s.getDiscrepancyType() != null ? s.getDiscrepancyType().name() : null
                ))
                .toList();

        return ResponseEntity.ok(lines);
    }

    /**
     * GET /api/pumps/{pumpId}/reports/expenses?from=&to=
     *
     * Returns expense records for the date range as JSON.
     * Same data as the CSV export but in a structured format for on-screen display and PDF print.
     */
    @GetMapping("/expenses")
    public ResponseEntity<List<ExpenseReportLine>> getExpensesReport(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<ExpenseReportLine> lines = expenseRepository.findByPumpIdOrderByExpenseDateDescCreatedAtDesc(pumpId)
                .stream()
                .filter(e -> !e.getExpenseDate().isBefore(from) && !e.getExpenseDate().isAfter(to))
                .map(e -> new ExpenseReportLine(
                        e.getId(),
                        e.getExpenseDate(),
                        e.getCategory() != null ? e.getCategory().name() : null,
                        e.getAmount(),
                        e.getDescription(),
                        e.getApprovalStatus() != null ? e.getApprovalStatus().name() : null
                ))
                .toList();

        return ResponseEntity.ok(lines);
    }

    // ── Report line records ───────────────────────────────────────────────────

    public record ShiftReportLine(
            Long id,
            LocalDate shiftDate,
            String shiftName,
            String status,
            BigDecimal totalAmountDue,
            BigDecimal cashCollected,
            BigDecimal upiCollected,
            BigDecimal cardCollected,
            BigDecimal creditTotal,
            BigDecimal discrepancyAmount,
            String discrepancyType
    ) {}

    public record ExpenseReportLine(
            Long id,
            LocalDate expenseDate,
            String category,
            BigDecimal amount,
            String description,
            String approvalStatus
    ) {}
}
