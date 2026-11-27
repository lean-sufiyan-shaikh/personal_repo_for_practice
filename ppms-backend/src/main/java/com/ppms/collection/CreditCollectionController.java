package com.ppms.collection;

import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditLedgerController;
import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/** Collecting overdue credit: the dashboard, today's reminders, promises to pay and the monthly statements. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/collections")
@RequiredArgsConstructor
public class CreditCollectionController {

    private final CollectionDashboardService dashboardService;
    private final CreditFollowUpService followUpService;
    private final CreditPromiseService promiseService;
    private final MonthlyStatementService statementService;
    private final CreditClientAccessService clientAccessService;
    private final BusinessClock businessClock;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'ACCOUNTANT')")
    public CollectionDashboardResponse getDashboard(@PathVariable Long pumpId) {
        return dashboardService.dashboardOf(pumpId);
    }

    @GetMapping("/reminders/due")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public List<ReminderDueResponse> getDueReminders(@PathVariable Long pumpId) {
        return followUpService.dueReminders(pumpId);
    }

    @PostMapping("/clients/{clientId}/reminders")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ReminderResponse> recordReminder(@PathVariable Long pumpId, @PathVariable Long clientId,
                                                           @Valid @RequestBody RecordReminderRequest request,
                                                           @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(followUpService.recordReminder(pumpId, clientId, request, currentUser));
    }

    @GetMapping("/clients/{clientId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'ACCOUNTANT')")
    public ClientFollowUpResponse getClientFollowUp(@PathVariable Long pumpId, @PathVariable Long clientId) {
        return followUpService.followUpOf(pumpId, clientId);
    }

    @PostMapping("/clients/{clientId}/promises")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<PromiseResponse> recordPromise(@PathVariable Long pumpId, @PathVariable Long clientId,
                                                         @Valid @RequestBody RecordPromiseRequest request,
                                                         @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(promiseService.recordPromise(pumpId, clientId, request, currentUser));
    }

    @PutMapping("/promises/{promiseId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public PromiseResponse editPromise(@PathVariable Long pumpId, @PathVariable Long promiseId,
                                       @Valid @RequestBody EditPromiseRequest request,
                                       @AuthenticationPrincipal User currentUser) {
        return promiseService.editPromise(pumpId, promiseId, request, currentUser);
    }

    @PostMapping("/promises/{promiseId}/cancel")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public PromiseResponse cancelPromise(@PathVariable Long pumpId, @PathVariable Long promiseId,
                                         @Valid @RequestBody CancelPromiseRequest request,
                                         @AuthenticationPrincipal User currentUser) {
        return promiseService.cancelPromise(pumpId, promiseId, request, currentUser);
    }

    /** {@code ?month=2026-10} lists the pump's statements of that month; {@code ?clientId=} lists one client's. */
    @GetMapping("/statements")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'ACCOUNTANT')")
    public List<MonthlyStatementResponse> getStatements(@PathVariable Long pumpId,
                                                        @RequestParam(required = false) YearMonth month,
                                                        @RequestParam(required = false) Long clientId) {
        if (clientId != null) {
            clientAccessService.requireClientForPump(pumpId, clientId);
            return statementService.statementsOfClient(pumpId, clientId);
        }
        return statementService.statementsOfMonth(pumpId, month != null ? month : YearMonth.from(businessClock.today()).minusMonths(1));
    }

    /** The statement page exactly as it was generated, ready for browser printing. */
    @GetMapping(value = "/statements/{statementId}", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<String> getStatementPage(@PathVariable Long pumpId, @PathVariable Long statementId) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy", CreditLedgerController.STATEMENT_CONTENT_SECURITY_POLICY)
                .body(statementService.storedPage(pumpId, statementId));
    }
}
