package com.ppms.export;

import com.ppms.common.time.DateRangeBounds;
import com.ppms.expense.PumpExpense;
import com.ppms.expense.PumpExpenseRepository;
import com.ppms.report.ReportController;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Provides CSV download endpoints for key data exports.
 * No external library is used — CSV is built with a simple StringBuilder.
 * All exports are restricted to Owner and Admin.
 *
 * Endpoints:
 *   GET /api/pumps/{pumpId}/export/shifts?from=YYYY-MM-DD&to=YYYY-MM-DD
 *   GET /api/pumps/{pumpId}/export/expenses?from=YYYY-MM-DD&to=YYYY-MM-DD
 */
@Slf4j
@RestController
@RequestMapping("/api/pumps")
@RequiredArgsConstructor
public class CsvExportController {

    private final ShiftRepository       shiftRepository;
    private final PumpExpenseRepository expenseRepository;
    private final UserRepository        userRepository;

    /**
     * GET /api/pumps/{pumpId}/export/shifts
     * Downloads a CSV of closed shift summaries for the given date range.
     */
    @GetMapping("/{pumpId}/export/shifts")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<byte[]> exportShifts(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User currentUser) {
        DateRangeBounds.requireRangeOfAtMost(from, to, ReportController.MAX_SUMMARY_RANGE_DAYS);
        List<Shift> shifts = shiftRepository.findClosedShiftsByDateRange(pumpId, from, to);
        Map<Long, String> operatorNameById = userRepository.findAllById(
                        shifts.stream().map(Shift::getOperatorId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        StringBuilder csv = new StringBuilder();
        csv.append("Shift ID,Date,Operator,Window,Status,Total Amount Due,Cash Collected,UPI Collected,"
                + "Card Collected,Fleet Card Collected,Credit Total,Discrepancy,Short/Over\n");

        for (Shift s : shifts) {
            csv.append(s.getId()).append(',')
               .append(CsvCells.text(s.getShiftDate())).append(',')
               .append(CsvCells.text(operatorNameById.get(s.getOperatorId()))).append(',')
               .append(CsvCells.text(s.getShiftName())).append(',')
               .append(CsvCells.text(s.getStatus())).append(',')
               .append(CsvCells.number(s.getTotalAmountDue())).append(',')
               .append(CsvCells.number(s.getCashCollected())).append(',')
               .append(CsvCells.number(s.getUpiCollected())).append(',')
               .append(CsvCells.number(s.getCardCollected())).append(',')
               .append(CsvCells.number(s.getFleetCardCollected())).append(',')
               .append(CsvCells.number(s.getCreditTotal())).append(',')
               .append(CsvCells.number(s.getDiscrepancyAmount())).append(',')
               .append(CsvCells.text(s.getDiscrepancyType())).append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String filename = "shifts_" + from + "_to_" + to + ".csv";

        log.info("CSV export: pump={} type=shifts from={} to={} rows={} by={}",
                pumpId, from, to, shifts.size(), currentUser.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    /**
     * GET /api/pumps/{pumpId}/export/expenses
     * Downloads a CSV of all expense records for the given date range.
     */
    @GetMapping("/{pumpId}/export/expenses")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<byte[]> exportExpenses(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User currentUser) {
        DateRangeBounds.requireRangeOfAtMost(from, to, ReportController.MAX_SUMMARY_RANGE_DAYS);
        List<PumpExpense> expenses =
                expenseRepository.findByPumpIdAndExpenseDateBetweenOrderByExpenseDateDescCreatedAtDesc(pumpId, from, to);

        StringBuilder csv = new StringBuilder();
        csv.append("Expense ID,Date,Category,Amount,Description\n");

        for (PumpExpense e : expenses) {
            csv.append(e.getId()).append(',')
               .append(CsvCells.text(e.getExpenseDate())).append(',')
               .append(CsvCells.text(e.getCategory())).append(',')
               .append(CsvCells.number(e.getAmount())).append(',')
               .append(CsvCells.text(e.getDescription())).append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String filename = "expenses_" + from + "_to_" + to + ".csv";

        log.info("CSV export: pump={} type=expenses from={} to={} rows={} by={}",
                pumpId, from, to, expenses.size(), currentUser.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }
}
