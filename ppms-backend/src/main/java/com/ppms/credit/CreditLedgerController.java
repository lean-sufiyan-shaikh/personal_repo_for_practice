package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/pumps/{pumpId}/credit-ledger")
@RequiredArgsConstructor
public class CreditLedgerController {

    private final CreditClientRepository clientRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final CreditInterestService interestService;
    private final CreditLedgerService ledgerService;
    private final UserRepository userRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;

    /**
     * GET /api/pumps/{pumpId}/credit-ledger
     * Returns all credit clients for the pump with their current outstanding balance.
     */
    @GetMapping
    public ResponseEntity<List<CreditClientResponse>> getLedgerSummary(@PathVariable Long pumpId) {
        List<CreditClient> clients = clientRepository.findByPumpIdOrderByNameAsc(pumpId);

        // Batch-resolve individual outstanding balances in 3 DB queries total
        List<Long> clientIds = clients.stream().map(CreditClient::getId).toList();
        Map<Long, BigDecimal> outstandingMap = interestService.computeOutstandingBatch(clientIds);

        // Determine which root accounts have sub-accounts
        Set<Long> idsWithChildren = clients.stream()
                .filter(c -> c.getParentClientId() != null)
                .map(CreditClient::getParentClientId)
                .collect(Collectors.toSet());

        // Build name map for resolving parent names without N+1 queries
        Map<Long, String> nameById = clients.stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));

        List<CreditClientResponse> response = clients.stream()
                .map(c -> {
                    boolean isParent = c.getParentClientId() == null && idsWithChildren.contains(c.getId());
                    BigDecimal outstanding;
                    if (isParent) {
                        BigDecimal childrenSum = clients.stream()
                                .filter(child -> c.getId().equals(child.getParentClientId()))
                                .map(child -> outstandingMap.getOrDefault(child.getId(), BigDecimal.ZERO))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                        outstanding = outstandingMap.getOrDefault(c.getId(), BigDecimal.ZERO).add(childrenSum);
                    } else {
                        outstanding = outstandingMap.getOrDefault(c.getId(), BigDecimal.ZERO);
                    }
                    return CreditClientResponse.builder()
                            .id(c.getId())
                            .pumpId(c.getPumpId())
                            .name(c.getName())
                            .phone(c.getPhone())
                            .notes(c.getNotes())
                            .creditLimit(c.getCreditLimit())
                            .outstandingBalance(outstanding.setScale(2, RoundingMode.HALF_UP))
                            .monthlyInterestRate(c.getMonthlyInterestRate())
                            .interestGraceDays(c.getInterestGraceDays())
                            .createdAt(c.getCreatedAt())
                            .parentClientId(c.getParentClientId())
                            .parentClientName(c.getParentClientId() != null ? nameById.get(c.getParentClientId()) : null)
                            .isParent(isParent)
                            .build();
                })
                .sorted(Comparator
                        .comparing(CreditClientResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CreditClientResponse::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/pumps/{pumpId}/credit-ledger/{clientId}/transactions?page=0&size=50
     * Returns a paginated ledger for a client — credit sales interleaved with payments
     * and interest charges, with a running balance.
     *
     * Note: running balance is computed across ALL transactions before pagination so the
     * balance values on each page are always correct relative to the full history.
     */
    @GetMapping("/{clientId}/transactions")
    public ResponseEntity<PagedResponse<CreditTransactionResponse>> getTransactions(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        List<CreditTransactionResponse> all = new ArrayList<>();

        // Sales (debits)
        creditEntryRepository.findByClientIdOrderByCreatedAtDesc(clientId)
                .forEach(sale -> all.add(CreditTransactionResponse.builder()
                        .type("SALE")
                        .referenceId(sale.getId())
                        .reference(sale.getDescription())
                        .detail(sale.getFuelType())
                        .amount(sale.getAmount().setScale(2, RoundingMode.HALF_UP))
                        .occurredAt(sale.getCreatedAt())
                        .build()));

        // Payments (credits) — batch-load user names to avoid N+1
        List<CreditPayment> payments = paymentRepository.findByClientIdOrderByPaidAtDesc(clientId);
        Set<Long> paymentUserIds = payments.stream().map(CreditPayment::getRecordedById).collect(Collectors.toSet());
        Map<Long, String> paymentUserNames = userRepository.findAllById(paymentUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        payments.forEach(payment -> {
            String recordedBy = paymentUserNames.getOrDefault(payment.getRecordedById(), "Unknown");
            all.add(CreditTransactionResponse.builder()
                    .type("PAYMENT")
                    .referenceId(payment.getId())
                    .reference(payment.getNotes() != null ? payment.getNotes() : recordedBy)
                    .detail(payment.getPaymentMode().name())
                    .amount(payment.getAmount().setScale(2, RoundingMode.HALF_UP))
                    .occurredAt(payment.getCreatedAt())
                    .build());
        });

        // Interest charges (debits)
        interestChargeRepository.findByClientIdOrderByCreatedAtDesc(clientId)
                .forEach(charge -> {
                    String label = String.format("%d days @ %.2f%%/month", charge.getDaysApplied(), charge.getRateApplied());
                    all.add(CreditTransactionResponse.builder()
                            .type("INTEREST")
                            .referenceId(charge.getId())
                            .reference(label)
                            .detail(String.format("%s – %s", charge.getPeriodFrom(), charge.getPeriodTo()))
                            .amount(charge.getAmount().setScale(2, RoundingMode.HALF_UP))
                            .occurredAt(charge.getCreatedAt())
                            .build());
                });

        // Bug 3 fix: secondary sort on type then referenceId ensures deterministic order when
        // two transactions share the exact same timestamp, preventing running balance drift
        // across repeated requests.
        all.sort(Comparator.comparing(CreditTransactionResponse::getOccurredAt)
                .thenComparing(CreditTransactionResponse::getType)
                .thenComparing(CreditTransactionResponse::getReferenceId));

        // Compute running balance oldest → newest
        BigDecimal runningBalance = BigDecimal.ZERO;
        for (CreditTransactionResponse tx : all) {
            if ("PAYMENT".equals(tx.getType())) {
                runningBalance = runningBalance.subtract(tx.getAmount());
            } else {
                runningBalance = runningBalance.add(tx.getAmount());
            }
            tx.setRunningBalance(runningBalance.setScale(2, RoundingMode.HALF_UP));
        }

        // Reverse: newest first for display
        all.sort(Comparator.comparing(CreditTransactionResponse::getOccurredAt).reversed()
                .thenComparing(CreditTransactionResponse::getType)
                .thenComparing(CreditTransactionResponse::getReferenceId));

        return ResponseEntity.ok(PagedResponse.of(all, page, size));
    }

    /**
     * POST /api/pumps/{pumpId}/credit-ledger/{clientId}/payments
     * Records a payment settlement against the client's outstanding balance.
     * Owner/Admin only.
     *
     * Bug 10: @Transactional moved to CreditLedgerService.recordPayment().
     * Bug 1: overpayment guard now uses combined outstanding for parent accounts.
     */
    @PostMapping("/{clientId}/payments")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditPaymentResponse> recordPayment(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @Valid @RequestBody RecordPaymentRequest request,
            @AuthenticationPrincipal User currentUser) {

        CreditPayment payment = ledgerService.recordPayment(pumpId, clientId, request, currentUser);

        auditService.log(pumpId, AuditAction.CREDIT_PAYMENT_RECEIVED,
                "CreditPayment", payment.getId().toString(),
                "Payment ₹" + payment.getAmount() + " (" + payment.getPaymentMode() + ") for clientId=" + clientId,
                currentUser);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toPaymentResponse(payment, currentUser.getFullName()));
    }

    /**
     * GET /api/pumps/{pumpId}/credit-ledger/{clientId}/payments
     * Returns all payment records for a client, newest first.
     */
    @GetMapping("/{clientId}/payments")
    public ResponseEntity<List<CreditPaymentResponse>> getPayments(
            @PathVariable Long pumpId,
            @PathVariable Long clientId) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        List<CreditPayment> paymentList = paymentRepository.findByClientIdOrderByPaidAtDesc(clientId);
        Set<Long> userIds = paymentList.stream().map(CreditPayment::getRecordedById).collect(Collectors.toSet());
        Map<Long, String> userNames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        List<CreditPaymentResponse> payments = paymentList.stream()
                .map(p -> toPaymentResponse(p, userNames.getOrDefault(p.getRecordedById(), "Unknown")))
                .toList();

        return ResponseEntity.ok(payments);
    }

    /**
     * PATCH /api/pumps/{pumpId}/credit-ledger/clients/{clientId}/credit-limit
     * Updates the credit limit for a client. 0 = no limit.
     *
     * Bug 4: toClientResponse() N+1 fixed by pre-loading all pump clients in one query.
     */
    @PatchMapping("/clients/{clientId}/credit-limit")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditClientResponse> updateCreditLimit(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @Valid @RequestBody UpdateCreditLimitRequest request,
            @AuthenticationPrincipal User currentUser) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        BigDecimal oldLimit = client.getCreditLimit();
        client.setCreditLimit(request.getCreditLimit().setScale(2, RoundingMode.HALF_UP));
        client = clientRepository.save(client);

        log.info("Credit limit updated: pump={}, client={}, newLimit={}", pumpId, clientId, client.getCreditLimit());

        auditService.log(pumpId, AuditAction.CREDIT_LIMIT_CHANGED,
                "CreditClient", clientId.toString(),
                "Credit limit for " + client.getName() + " changed from ₹" + oldLimit + " to ₹" + client.getCreditLimit(),
                currentUser);

        List<CreditClient> allPumpClients = clientRepository.findByPumpIdOrderByNameAsc(pumpId);
        return ResponseEntity.ok(toClientResponse(client, allPumpClients));
    }

    /**
     * PATCH /api/pumps/{pumpId}/credit-ledger/clients/{clientId}/interest-settings
     * Updates monthly interest rate and grace days for a client. Owner/Admin only.
     *
     * Bug 4: toClientResponse() N+1 fixed by pre-loading all pump clients in one query.
     */
    @PatchMapping("/clients/{clientId}/interest-settings")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditClientResponse> updateInterestSettings(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @Valid @RequestBody UpdateInterestSettingsRequest request) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        client.setMonthlyInterestRate(request.monthlyInterestRate().setScale(2, RoundingMode.HALF_UP));
        client.setInterestGraceDays(request.interestGraceDays());
        client = clientRepository.save(client);

        log.info("Interest settings updated: pump={}, client={}, rate={}%, graceDays={}",
                pumpId, clientId, client.getMonthlyInterestRate(), client.getInterestGraceDays());

        List<CreditClient> allPumpClients = clientRepository.findByPumpIdOrderByNameAsc(pumpId);
        return ResponseEntity.ok(toClientResponse(client, allPumpClients));
    }

    /**
     * POST /api/pumps/{pumpId}/credit-ledger/{clientId}/interest/apply
     * Applies pro-rata interest for a single client up to today. Owner/Admin only.
     *
     * Bug 10: @Transactional is on CreditInterestService.applyProRata(), not here.
     */
    @PostMapping("/{clientId}/interest/apply")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Map<String, Object>> applyInterest(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @AuthenticationPrincipal User currentUser) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        return interestService.applyProRata(pumpId, clientId, currentUser.getId())
                .map(charge -> ResponseEntity.ok(Map.<String, Object>of(
                        "applied", true,
                        "amount", charge.getAmount(),
                        "days", charge.getDaysApplied(),
                        "periodFrom", charge.getPeriodFrom().toString(),
                        "periodTo", charge.getPeriodTo().toString()
                )))
                .orElseGet(() -> ResponseEntity.ok(Map.of("applied", false,
                        "reason", "No eligible interest period — client may have no balance, no rate configured, or interest was already applied recently.")));
    }

    /**
     * POST /api/pumps/{pumpId}/credit-ledger/interest/apply-all
     * Applies pro-rata interest to ALL eligible clients for the pump. Owner/Admin only.
     *
     * Bug 10: @Transactional is on CreditInterestService.applyProRataForAllClients(), not here.
     */
    @PostMapping("/interest/apply-all")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Map<String, Object>> applyInterestForAll(
            @PathVariable Long pumpId,
            @AuthenticationPrincipal User currentUser) {

        int charged = interestService.applyProRataForAllClients(pumpId, currentUser.getId());
        return ResponseEntity.ok(Map.of("clientsCharged", charged));
    }

    /**
     * GET /api/pumps/{pumpId}/credit-ledger/{clientId}/interest/total-recovered
     * Returns the total interest ever charged to this client and all its sub-accounts.
     * For standalone or sub-accounts, returns only the client's own interest total.
     */
    @GetMapping("/{clientId}/interest/total-recovered")
    public ResponseEntity<Map<String, Object>> getTotalInterestRecovered(
            @PathVariable Long pumpId,
            @PathVariable Long clientId) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        BigDecimal total = interestService.computeTotalInterestRecovered(clientId);
        return ResponseEntity.ok(Map.of("totalInterestRecovered", total));
    }

    /**
     * DELETE /api/pumps/{pumpId}/credit-ledger/{clientId}/interest/{chargeId}
     * Permanently removes an interest charge from the ledger. Owner/Admin only.
     *
     * Bug 10: @Transactional moved to CreditLedgerService.deleteInterestCharge().
     */
    @DeleteMapping("/{clientId}/interest/{chargeId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> deleteInterestCharge(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @PathVariable Long chargeId,
            @AuthenticationPrincipal User currentUser) {

        ledgerService.deleteInterestCharge(pumpId, clientId, chargeId, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/pumps/{pumpId}/credit-ledger/entries/{entryId}/reassign
     * Moves a credit entry from its current account to a different account. Owner/Admin only.
     *
     * Bug 6: audit trail no longer stores from==to for previously unlinked entries.
     * Bug 10: @Transactional moved to CreditLedgerService.reassignCreditEntry().
     */
    @PostMapping("/entries/{entryId}/reassign")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> reassignCreditEntry(
            @PathVariable Long pumpId,
            @PathVariable Long entryId,
            @Valid @RequestBody ReassignCreditEntryRequest request,
            @AuthenticationPrincipal User currentUser) {

        ledgerService.reassignCreditEntry(pumpId, entryId, request, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/reassign
     * Moves a recorded payment to another account within the same parent/sub-account group.
     * Owner/Admin only.
     */
    @PostMapping("/payments/{paymentId}/reassign")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> reassignCreditPayment(
            @PathVariable Long pumpId,
            @PathVariable Long paymentId,
            @Valid @RequestBody ReassignCreditEntryRequest request,
            @AuthenticationPrincipal User currentUser) {

        ledgerService.reassignCreditPayment(pumpId, paymentId, request, currentUser);
        auditService.log(pumpId, AuditAction.CREDIT_ENTRY_REASSIGNED,
                "CreditPayment", paymentId.toString(),
                "Payment moved to clientId=" + request.getToClientId() + " reason=" + request.getReason().trim(),
                currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/pumps/{pumpId}/credit-ledger/{clientId}/statement
     *
     * Returns an HTML credit account statement for the client, ready for browser printing.
     * The frontend opens this in a new tab and calls window.print().
     *
     * Optional query params:
     *   fromDate (ISO date, e.g. 2024-01-01) — filter transactions from this date
     *   toDate   (ISO date, e.g. 2024-12-31) — filter transactions up to this date
     *
     * The statement includes: pump header, client details, transaction table with running
     * balance, and the current outstanding balance as a footer summary.
     */
    @GetMapping(value = "/{clientId}/statement", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getCreditStatement(
            @PathVariable Long pumpId,
            @PathVariable Long clientId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Pump not found"));

        // Build full transaction list (chronological, oldest first)
        List<CreditTransactionResponse> all = buildTransactionList(clientId);
        if (fromDate != null) {
            all = all.stream()
                    .filter(tx -> !tx.getOccurredAt().toLocalDate().isBefore(fromDate))
                    .collect(Collectors.toList());
        }
        if (toDate != null) {
            all = all.stream()
                    .filter(tx -> !tx.getOccurredAt().toLocalDate().isAfter(toDate))
                    .collect(Collectors.toList());
        }

        BigDecimal outstanding = interestService.computeOutstanding(clientId);
        String html = buildStatementHtml(pump, client, all, outstanding, fromDate, toDate);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /**
     * Builds the sorted, balance-computed transaction list for a client.
     * Returns oldest-first order (correct for a printed statement).
     */
    private List<CreditTransactionResponse> buildTransactionList(Long clientId) {
        List<CreditTransactionResponse> all = new ArrayList<>();

        creditEntryRepository.findByClientIdOrderByCreatedAtDesc(clientId)
                .forEach(sale -> all.add(CreditTransactionResponse.builder()
                        .type("SALE")
                        .referenceId(sale.getId())
                        .reference(sale.getDescription())
                        .detail(sale.getFuelType())
                        .amount(sale.getAmount().setScale(2, RoundingMode.HALF_UP))
                        .occurredAt(sale.getCreatedAt())
                        .build()));

        List<CreditPayment> payments = paymentRepository.findByClientIdOrderByPaidAtDesc(clientId);
        Set<Long> paymentUserIds = payments.stream().map(CreditPayment::getRecordedById).collect(Collectors.toSet());
        Map<Long, String> paymentUserNames = userRepository.findAllById(paymentUserIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        payments.forEach(payment -> {
            String recordedBy = paymentUserNames.getOrDefault(payment.getRecordedById(), "Unknown");
            all.add(CreditTransactionResponse.builder()
                    .type("PAYMENT")
                    .referenceId(payment.getId())
                    .reference(payment.getNotes() != null ? payment.getNotes() : recordedBy)
                    .detail(payment.getPaymentMode().name())
                    .amount(payment.getAmount().setScale(2, RoundingMode.HALF_UP))
                    .occurredAt(payment.getCreatedAt())
                    .build());
        });

        interestChargeRepository.findByClientIdOrderByCreatedAtDesc(clientId)
                .forEach(charge -> {
                    String label = String.format("%d days @ %.2f%%/month", charge.getDaysApplied(), charge.getRateApplied());
                    all.add(CreditTransactionResponse.builder()
                            .type("INTEREST")
                            .referenceId(charge.getId())
                            .reference(label)
                            .detail(String.format("%s – %s", charge.getPeriodFrom(), charge.getPeriodTo()))
                            .amount(charge.getAmount().setScale(2, RoundingMode.HALF_UP))
                            .occurredAt(charge.getCreatedAt())
                            .build());
                });

        // Chronological order for printed statement
        all.sort(Comparator.comparing(CreditTransactionResponse::getOccurredAt)
                .thenComparing(CreditTransactionResponse::getType)
                .thenComparing(CreditTransactionResponse::getReferenceId));

        // Compute running balance
        BigDecimal runningBalance = BigDecimal.ZERO;
        for (CreditTransactionResponse tx : all) {
            if ("PAYMENT".equals(tx.getType())) {
                runningBalance = runningBalance.subtract(tx.getAmount());
            } else {
                runningBalance = runningBalance.add(tx.getAmount());
            }
            tx.setRunningBalance(runningBalance.setScale(2, RoundingMode.HALF_UP));
        }

        return all;
    }

    /**
     * Generates a self-contained HTML string for the credit statement.
     * Includes inline CSS so the print layout is correct without external dependencies.
     */
    private String buildStatementHtml(PumpLocation pump, CreditClient client,
                                      List<CreditTransactionResponse> txns,
                                      BigDecimal outstanding,
                                      LocalDate fromDate, LocalDate toDate) {
        StringBuilder sb = new StringBuilder();
        String periodLabel = (fromDate != null || toDate != null)
                ? "Period: " + (fromDate != null ? fromDate : "inception") + " to " + (toDate != null ? toDate : "today")
                : "All transactions";

        sb.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"/>
                <title>Credit Statement — %s</title>
                <style>
                  body { font-family: Arial, sans-serif; font-size: 12px; margin: 20px; color: #333; }
                  h1 { font-size: 18px; margin: 0; }
                  h2 { font-size: 14px; margin: 4px 0; color: #555; }
                  .header { border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 16px; }
                  .meta-row { display: flex; justify-content: space-between; margin-bottom: 12px; }
                  .meta-block p { margin: 2px 0; }
                  table { width: 100%%; border-collapse: collapse; margin-top: 10px; }
                  th { background: #f0f0f0; border: 1px solid #ccc; padding: 6px 8px; text-align: left; font-size: 11px; }
                  td { border: 1px solid #ddd; padding: 5px 8px; font-size: 11px; vertical-align: top; }
                  .amount { text-align: right; font-family: monospace; }
                  .balance { text-align: right; font-family: monospace; font-weight: bold; }
                  .type-PAYMENT { color: #1a7a1a; }
                  .type-INTEREST { color: #a05000; }
                  .type-SALE { color: #333; }
                  .summary { margin-top: 20px; text-align: right; }
                  .outstanding { font-size: 14px; font-weight: bold; }
                  .no-print { margin-top: 20px; }
                  @media print { .no-print { display: none; } }
                </style>
                </head>
                <body>
                """.formatted(client.getName()));

        // Header
        sb.append("<div class='header'>")
          .append("<h1>").append(escapeHtml(pump.getName())).append("</h1>")
          .append("<h2>").append(escapeHtml(pump.getAddress())).append("</h2>")
          .append("<h2 style='margin-top:8px;color:#333;'>CREDIT ACCOUNT STATEMENT</h2>")
          .append("</div>");

        // Client info + period
        sb.append("<div class='meta-row'>")
          .append("<div class='meta-block'>")
          .append("<p><strong>Account:</strong> ").append(escapeHtml(client.getName())).append("</p>");
        if (client.getPhone() != null) {
            sb.append("<p><strong>Phone:</strong> ").append(escapeHtml(client.getPhone())).append("</p>");
        }
        if (client.getCreditLimit() != null && client.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("<p><strong>Credit Limit:</strong> ₹").append(client.getCreditLimit().toPlainString()).append("</p>");
        }
        sb.append("</div>")
          .append("<div class='meta-block' style='text-align:right;'>")
          .append("<p>").append(periodLabel).append("</p>")
          .append("<p>Printed: ").append(LocalDate.now()).append("</p>")
          .append("</div></div>");

        // Transactions table
        if (txns.isEmpty()) {
            sb.append("<p><em>No transactions found for the selected period.</em></p>");
        } else {
            sb.append("<table><thead><tr>")
              .append("<th>Date</th><th>Type</th><th>Description</th>")
              .append("<th class='amount'>Debit (₹)</th><th class='amount'>Credit (₹)</th>")
              .append("<th class='balance'>Balance (₹)</th>")
              .append("</tr></thead><tbody>");

            for (CreditTransactionResponse tx : txns) {
                String typeClass = "type-" + tx.getType();
                String debit  = "SALE".equals(tx.getType()) || "INTEREST".equals(tx.getType())
                        ? tx.getAmount().toPlainString() : "";
                String credit = "PAYMENT".equals(tx.getType()) ? tx.getAmount().toPlainString() : "";
                String desc = tx.getReference() != null ? escapeHtml(tx.getReference()) : "";
                if (tx.getDetail() != null) desc += " <span style='color:#888;'>(" + escapeHtml(tx.getDetail()) + ")</span>";

                sb.append("<tr class='").append(typeClass).append("'>")
                  .append("<td>").append(tx.getOccurredAt().toLocalDate()).append("</td>")
                  .append("<td>").append(tx.getType()).append("</td>")
                  .append("<td>").append(desc).append("</td>")
                  .append("<td class='amount'>").append(debit).append("</td>")
                  .append("<td class='amount'>").append(credit).append("</td>")
                  .append("<td class='balance'>").append(tx.getRunningBalance().toPlainString()).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        // Summary
        sb.append("<div class='summary'>")
          .append("<p class='outstanding'>Outstanding Balance: ₹")
          .append(outstanding.setScale(2, RoundingMode.HALF_UP).toPlainString())
          .append("</p></div>");

        // Print button (hidden when printing)
        sb.append("<div class='no-print'><button onclick='window.print()' ")
          .append("style='padding:8px 20px;font-size:13px;cursor:pointer;'>Print Statement</button></div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    /** Minimal HTML escaping for user-supplied strings. */
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Bug 4 fix: builds a CreditClientResponse using a pre-loaded list of all pump clients,
     * eliminating the original N+1 pattern (findByParentClientId + findById per call).
     *
     * @param client         the saved entity to convert
     * @param allPumpClients all clients for this pump, pre-loaded in the calling method
     */
    private CreditClientResponse toClientResponse(CreditClient client, List<CreditClient> allPumpClients) {
        Map<Long, String> nameById = allPumpClients.stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));
        Set<Long> idsWithChildren = allPumpClients.stream()
                .filter(c -> c.getParentClientId() != null)
                .map(CreditClient::getParentClientId)
                .collect(Collectors.toSet());

        boolean isParent = client.getParentClientId() == null && idsWithChildren.contains(client.getId());
        BigDecimal outstanding = isParent
                ? interestService.computeOutstandingWithChildren(client.getId())
                : interestService.computeOutstanding(client.getId());
        String parentName = client.getParentClientId() != null
                ? nameById.get(client.getParentClientId())
                : null;

        return CreditClientResponse.builder()
                .id(client.getId())
                .pumpId(client.getPumpId())
                .name(client.getName())
                .phone(client.getPhone())
                .notes(client.getNotes())
                .creditLimit(client.getCreditLimit())
                .outstandingBalance(outstanding)
                .monthlyInterestRate(client.getMonthlyInterestRate())
                .interestGraceDays(client.getInterestGraceDays())
                .createdAt(client.getCreatedAt())
                .parentClientId(client.getParentClientId())
                .parentClientName(parentName)
                .isParent(isParent)
                .build();
    }

    /**
     * GET /api/pumps/{pumpId}/credit-ledger/payments/pending
     * Returns all credit payments awaiting Owner/Admin approval for this pump.
     * Only OWNER or ADMIN may view and act on the approval queue.
     */
    @GetMapping("/payments/pending")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<List<CreditPaymentResponse>> getPendingPayments(
            @PathVariable Long pumpId) {

        List<CreditPayment> pending = paymentRepository.findPendingApprovalByPumpId(pumpId);

        Set<Long> userIds = pending.stream().map(CreditPayment::getRecordedById).collect(Collectors.toSet());
        Map<Long, String> nameById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        return ResponseEntity.ok(pending.stream()
                .map(p -> toPaymentResponse(p, nameById.getOrDefault(p.getRecordedById(), "Unknown")))
                .toList());
    }

    /**
     * PATCH /api/pumps/{pumpId}/credit-ledger/payments/{paymentId}/approve
     * Approves or rejects a PENDING_APPROVAL credit payment.
     * Accepted status values: APPROVED, REJECTED.
     * Once approved, the payment is counted in the client's outstanding balance.
     */
    @PatchMapping("/payments/{paymentId}/approve")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditPaymentResponse> approvePayment(
            @PathVariable Long pumpId,
            @PathVariable Long paymentId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User currentUser) {

        String statusStr = body.get("status");
        if (statusStr == null) {
            throw new BusinessException("status is required (APPROVED or REJECTED)");
        }

        com.ppms.expense.ExpenseApprovalStatus newStatus;
        try {
            newStatus = com.ppms.expense.ExpenseApprovalStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid status. Allowed: APPROVED, REJECTED");
        }

        if (newStatus == com.ppms.expense.ExpenseApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException("Cannot set status back to PENDING_APPROVAL");
        }

        CreditPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (!payment.getPumpId().equals(pumpId)) {
            throw new BusinessException("Payment does not belong to this pump");
        }

        if (payment.getPaymentApprovalStatus() != com.ppms.expense.ExpenseApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException(
                    "Payment is already " + payment.getPaymentApprovalStatus() + " — only PENDING_APPROVAL payments can be acted on");
        }

        payment.setPaymentApprovalStatus(newStatus);
        payment.setApprovedByUserId(currentUser.getId());
        payment.setApprovedAt(java.time.OffsetDateTime.now());
        CreditPayment saved = paymentRepository.save(payment);

        log.info("Credit payment {}: paymentId={} pump={} amount={} by={}",
                newStatus, paymentId, pumpId, payment.getAmount(), currentUser.getId());

        auditService.log(pumpId, AuditAction.CREDIT_PAYMENT_RECEIVED,
                "CreditPayment", paymentId.toString(),
                "Payment " + newStatus + ": ₹" + payment.getAmount() + " for clientId=" + payment.getClientId(),
                currentUser);

        String recordedByName = userRepository.findById(saved.getRecordedById())
                .map(User::getFullName).orElse("Unknown");

        return ResponseEntity.ok(toPaymentResponse(saved, recordedByName));
    }

    private CreditPaymentResponse toPaymentResponse(CreditPayment p, String recordedByName) {
        return CreditPaymentResponse.builder()
                .id(p.getId())
                .clientId(p.getClientId())
                .amount(p.getAmount())
                .paymentMode(p.getPaymentMode().name())
                .referenceNo(p.getReferenceNo())
                .notes(p.getNotes())
                .paidAt(p.getPaidAt())
                .recordedByUserName(recordedByName)
                .paymentApprovalStatus(p.getPaymentApprovalStatus().name())
                .approvedByUserId(p.getApprovedByUserId())
                .approvedAt(p.getApprovedAt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
