package com.ppms.credit;

import com.ppms.fleet.VehicleUsageGroup;
import com.ppms.fleet.VehicleUsageService;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductQueryService;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.text.CodedText;
import com.ppms.common.text.ServerTextCodes;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CreditLedgerQueryService {

    private static final String ACTIVE = "ACTIVE";
    private static final String WAIVED = "WAIVED";
    private static final String COUNTER_SALE = "COUNTER_SALE";
    private static final String PAYMENT_REVERSAL = "PAYMENT_REVERSAL";
    private static final String BOUNCE_CHARGE = "BOUNCE_CHARGE";
    static final String UNKNOWN_STAFF_NAME = "Unknown staff";
    private static final String OPENING_BALANCE_LABEL = "Opening balance";
    private static final Comparator<CreditTransactionResponse> OLDEST_FIRST =
            Comparator.comparing(CreditTransactionResponse::getBusinessDate)
                    .thenComparing(CreditTransactionResponse::getOccurredAt)
                    .thenComparing(CreditTransactionResponse::getType)
                    .thenComparing(CreditTransactionResponse::getReferenceId);
    private static final Comparator<CreditTransactionResponse> NEWEST_FIRST =
            Comparator.comparing(CreditTransactionResponse::getBusinessDate).reversed()
                    .thenComparing(Comparator.comparing(CreditTransactionResponse::getOccurredAt).reversed())
                    .thenComparing(CreditTransactionResponse::getType)
                    .thenComparing(CreditTransactionResponse::getReferenceId);

    private final CreditClientRepository clientRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditPaymentReversalRepository reversalRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final AncillaryProductRepository ancillaryProductRepository;
    private final CreditOpeningBalanceRepository openingBalanceRepository;
    private final CreditAdjustmentRepository adjustmentRepository;
    private final CreditDisputeRepository disputeRepository;
    private final CreditDepositRepository depositRepository;
    private final CreditInterestService interestService;
    private final UserRepository userRepository;
    private final VehicleUsageService vehicleUsageService;

    public List<CreditClientResponse> getLedgerSummary(Long pumpId) {
        List<CreditClient> clients = clientRepository.findByPumpIdOrderByNameAsc(pumpId);
        List<Long> clientIds = clients.stream().map(CreditClient::getId).toList();
        Map<Long, BigDecimal> outstandingMap = interestService.computeOutstandingBatch(clientIds);
        Map<Long, CreditInterestService.InterestBreakdown> breakdownMap = interestService.computeInterestBreakdownBatch(clientIds);
        Map<Long, BigDecimal> depositHeldById = depositHeldByClientId(clientIds);
        Map<Long, List<CreditClient>> childrenByParentId = clients.stream()
                .filter(client -> client.getParentClientId() != null)
                .collect(Collectors.groupingBy(CreditClient::getParentClientId));
        Map<Long, String> nameById = clients.stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));

        return clients.stream()
                .map(client -> toClientResponse(client, outstandingMap, breakdownMap, childrenByParentId, nameById,
                        depositHeldById))
                .sorted(Comparator
                        .comparing(CreditClientResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CreditClientResponse::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public PagedResponse<CreditTransactionResponse> getTransactions(Long clientId, int page, int size) {
        List<CreditTransactionResponse> transactions = buildTransactionList(clientId);
        transactions.sort(NEWEST_FIRST);
        return PagedResponse.of(transactions, page, size);
    }

    public List<CreditPaymentResponse> getPayments(Long clientId) {
        return mapPayments(paymentRepository.findByClientIdOrderByPaidAtDesc(clientId));
    }

    public List<CreditPaymentResponse> getPendingPayments(Long pumpId) {
        return mapPayments(paymentRepository.findPendingApprovalByPumpId(pumpId));
    }

    public CreditClientResponse toClientResponse(CreditClient client) {
        List<CreditClient> family = new ArrayList<>();
        family.add(client);
        family.addAll(clientRepository.findByParentClientId(client.getId()));
        if (client.getParentClientId() != null) {
            clientRepository.findById(client.getParentClientId()).ifPresent(family::add);
        }
        List<Long> ids = family.stream().map(CreditClient::getId).toList();
        Map<Long, BigDecimal> outstandingByClientId = interestService.computeOutstandingBatch(ids);
        Map<Long, CreditInterestService.InterestBreakdown> breakdownMap = interestService.computeInterestBreakdownBatch(ids);
        Map<Long, List<CreditClient>> childrenByParentId = family.stream()
                .filter(item -> item.getParentClientId() != null)
                .collect(Collectors.groupingBy(CreditClient::getParentClientId));
        Map<Long, String> nameById = family.stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));
        return toClientResponse(client, outstandingByClientId, breakdownMap, childrenByParentId, nameById,
                depositHeldByClientId(ids));
    }

    public List<CreditTransactionResponse> getAllTransactions(CreditClient client, boolean includeChildren) {
        List<CreditClient> accounts = new ArrayList<>();
        accounts.add(client);
        if (includeChildren) {
            accounts.addAll(clientRepository.findByParentClientId(client.getId()));
        }
        List<CreditTransactionResponse> transactions = new ArrayList<>();
        for (CreditClient account : accounts) {
            List<CreditTransactionResponse> accountRows = buildTransactionList(account.getId());
            accountRows.forEach(row -> {
                row.setClientId(account.getId());
                row.setClientName(account.getName());
            });
            transactions.addAll(accountRows);
        }
        transactions.sort(NEWEST_FIRST);
        return transactions;
    }

    /** The period's rows and today's outstanding, with the credit of the period grouped by vehicle. */
    public CreditStatement getStatement(CreditClient client, LocalDate fromDate, LocalDate toDate) {
        CreditStatement allRows = statementOfAllRows(client);
        return new CreditStatement(filterByDate(allRows.transactions(), fromDate, toDate), allRows.outstanding(),
                allRows.subAccountNames(), vehicleUsageOf(client, fromDate, toDate), allRows.depositHeld());
    }

    /** A closed period's statement, whose outstanding is the balance at the end of its last day rather than today's. */
    public CreditStatement getPeriodStatement(CreditClient client, LocalDate fromDate, LocalDate toDate) {
        CreditStatement allRows = statementOfAllRows(client);
        BigDecimal closingBalance = allRows.transactions().stream()
                .filter(tx -> !tx.getBusinessDate().isAfter(toDate))
                .reduce((earlier, later) -> later)
                .map(CreditTransactionResponse::getRunningBalance)
                .orElse(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        return new CreditStatement(filterByDate(allRows.transactions(), fromDate, toDate), closingBalance,
                allRows.subAccountNames(), vehicleUsageOf(client, fromDate, toDate), allRows.depositHeld());
    }

    private List<VehicleUsageGroup> vehicleUsageOf(CreditClient client, LocalDate fromDate, LocalDate toDate) {
        List<CreditClient> accounts = new ArrayList<>();
        accounts.add(client);
        if (client.getParentClientId() == null) {
            accounts.addAll(clientRepository.findByParentClientId(client.getId()));
        }
        return vehicleUsageService.usageByVehicle(accounts, fromDate, toDate);
    }

    private CreditStatement statementOfAllRows(CreditClient client) {
        List<CreditClient> subAccounts = client.getParentClientId() == null
                ? clientRepository.findByParentClientId(client.getId())
                : List.of();
        if (subAccounts.isEmpty()) {
            return new CreditStatement(buildTransactionList(client.getId()),
                    interestService.computeOutstanding(client.getId()), List.of(), List.of(),
                    depositRepository.heldByClientId(client.getId()).setScale(2, RoundingMode.HALF_UP));
        }

        List<CreditClient> group = new ArrayList<>();
        group.add(client);
        group.addAll(subAccounts);
        List<CreditTransactionResponse> transactions = new ArrayList<>();
        for (CreditClient account : group) {
            List<CreditTransactionResponse> accountRows = collectTransactions(account.getId());
            accountRows.forEach(row -> {
                row.setClientId(account.getId());
                row.setClientName(account.getName());
            });
            transactions.addAll(accountRows);
        }
        transactions.sort(OLDEST_FIRST);
        applyRunningBalance(transactions);
        BigDecimal groupDeposit = depositHeldByClientId(group.stream().map(CreditClient::getId).toList()).values()
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new CreditStatement(transactions,
                interestService.computeOutstandingWithChildren(client.getId()),
                subAccounts.stream().map(CreditClient::getName).toList(), List.of(), groupDeposit);
    }

    private static List<CreditTransactionResponse> filterByDate(List<CreditTransactionResponse> transactions,
                                                                LocalDate fromDate, LocalDate toDate) {
        return transactions.stream()
                .filter(tx -> fromDate == null || !tx.getBusinessDate().isBefore(fromDate))
                .filter(tx -> toDate == null || !tx.getBusinessDate().isAfter(toDate))
                .toList();
    }

    public CreditPaymentResponse toPaymentResponse(CreditPayment payment, String recordedByName) {
        return CreditPaymentResponse.builder()
                .id(payment.getId())
                .clientId(payment.getClientId())
                .amount(payment.getAmount())
                .paymentMode(payment.getPaymentMode().name())
                .referenceNo(payment.getReferenceNo())
                .chequeBank(payment.getChequeBank())
                .chequeDate(payment.getChequeDate())
                .notes(payment.getNotes())
                .paidAt(payment.getPaidAt())
                .recordedById(payment.getRecordedById())
                .recordedByUserName(recordedByName)
                .paymentApprovalStatus(payment.getPaymentApprovalStatus().name())
                .approvedByUserId(payment.getApprovedByUserId())
                .approvedAt(payment.getApprovedAt())
                .createdAt(payment.getCreatedAt())
                .build();
    }

    private List<CreditPaymentResponse> mapPayments(List<CreditPayment> payments) {
        Map<Long, String> nameById = loadUserNames(payments.stream()
                .map(CreditPayment::getRecordedById)
                .collect(Collectors.toSet()));
        return payments.stream()
                .map(payment -> toPaymentResponse(payment, nameById.getOrDefault(payment.getRecordedById(), UNKNOWN_STAFF_NAME)))
                .toList();
    }

    /**
     * Centralizes ledger transaction assembly so controllers and HTML rendering reuse the
     * exact same ordering and running-balance rules.
     */
    private List<CreditTransactionResponse> buildTransactionList(Long clientId) {
        List<CreditTransactionResponse> transactions = collectTransactions(clientId);
        transactions.sort(OLDEST_FIRST);
        applyRunningBalance(transactions);
        return transactions;
    }

    private List<CreditTransactionResponse> collectTransactions(Long clientId) {
        List<CreditTransactionResponse> transactions = new ArrayList<>();

        openingBalanceRepository.findByClientIdIn(List.of(clientId))
                .forEach(balance -> transactions.add(CreditTransactionResponse.builder()
                        .type("OPENING_BALANCE")
                        .referenceId(balance.getId())
                        .reference(OPENING_BALANCE_LABEL)
                        .referenceCode(ServerTextCodes.CREDIT_OPENING_BALANCE)
                        .amount(balance.getAmount().setScale(2, RoundingMode.HALF_UP))
                        .occurredAt(balance.getCreatedAt())
                        .businessDate(balance.getAsOfDate())
                        .status(ACTIVE)
                        .countedInBalance(true)
                        .build()));

        creditEntryRepository.findByClientIdOrderByCreatedAtDesc(clientId)
                .forEach(sale -> transactions.add(CreditTransactionResponse.builder()
                        .type("SALE")
                        .referenceId(sale.getId())
                        .reference(sale.getDescription())
                        .detail(sale.getFuelType())
                        .amount(sale.getAmount().setScale(2, RoundingMode.HALF_UP))
                        .occurredAt(sale.getCreatedAt())
                        .businessDate(sale.getSaleDate())
                        .status(sale.getVoidStatus())
                        .countedInBalance(ACTIVE.equals(sale.getVoidStatus()))
                        .build()));

        List<AncillarySale> counterSales = ancillarySaleRepository.findByClientIdsAndPaymentMode(
                List.of(clientId), TransactionPaymentMode.CREDIT);
        Map<Long, AncillaryProduct> productsById = ancillaryProductRepository.findAllById(counterSales.stream()
                        .map(AncillarySale::getProductId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AncillaryProduct::getId, Function.identity()));
        counterSales.forEach(sale -> {
            CodedText label = counterSaleLabel(sale, productsById.get(sale.getProductId()));
            transactions.add(CreditTransactionResponse.builder()
                    .type(COUNTER_SALE)
                    .referenceId(sale.getId())
                    .reference(label.text())
                    .referenceCode(label.code())
                    .referenceParams(label.params())
                    .amount(sale.getTotalWithGst().setScale(2, RoundingMode.HALF_UP))
                    .occurredAt(sale.getCreatedAt())
                    .businessDate(sale.getSaleDate())
                    .status(ACTIVE)
                    .countedInBalance(true)
                    .build());
        });

        List<CreditPayment> payments = paymentRepository.findByClientIdOrderByPaidAtDesc(clientId);
        Map<Long, String> paymentUserNames = loadUserNames(payments.stream()
                .map(CreditPayment::getRecordedById)
                .collect(Collectors.toSet()));
        Map<Long, CreditPayment> paymentsById = payments.stream()
                .collect(Collectors.toMap(CreditPayment::getId, Function.identity()));
        List<CreditPaymentReversal> reversals = reversalRepository.findByClientId(clientId);
        Map<Long, Long> reversalIdByPaymentId = reversals.stream()
                .collect(Collectors.toMap(CreditPaymentReversal::getPaymentId, CreditPaymentReversal::getId));
        payments.forEach(payment -> {
            String recordedBy = paymentUserNames.get(payment.getRecordedById());
            boolean showsUnknownRecorder = payment.getNotes() == null && recordedBy == null;
            String reference = payment.getNotes() != null ? payment.getNotes() : recordedBy;
            transactions.add(CreditTransactionResponse.builder()
                    .type("PAYMENT")
                    .referenceId(payment.getId())
                    .reference(showsUnknownRecorder ? UNKNOWN_STAFF_NAME : reference)
                    .referenceCode(showsUnknownRecorder ? ServerTextCodes.UNKNOWN_STAFF : null)
                    .detail(payment.getPaymentMode().name())
                    .amount(payment.getAmount().setScale(2, RoundingMode.HALF_UP))
                    .occurredAt(payment.getCreatedAt())
                    .businessDate(payment.getPaidAt())
                    .status(payment.getPaymentApprovalStatus().name())
                    .countedInBalance(countsInLedgerBalance(payment.getPaymentApprovalStatus()))
                    .linkedReferenceId(reversalIdByPaymentId.get(payment.getId()))
                    .build());
        });
        reversals.forEach(reversal -> transactions.addAll(reversalRows(reversal, paymentsById.get(reversal.getPaymentId()))));

        interestChargeRepository.findByClientIdOrderByCreatedAtDesc(clientId)
                .forEach(charge -> {
                    String label = String.format("%d days @ %.2f%%/month", charge.getDaysApplied(), charge.getRateApplied());
                    Map<String, Object> labelParams = new LinkedHashMap<>();
                    labelParams.put("days", charge.getDaysApplied());
                    labelParams.put("rate", charge.getRateApplied().setScale(2, RoundingMode.HALF_UP));
                    transactions.add(CreditTransactionResponse.builder()
                            .type("INTEREST")
                            .referenceId(charge.getId())
                            .reference(label)
                            .referenceCode(ServerTextCodes.INTEREST_CHARGE_DETAILS)
                            .referenceParams(labelParams)
                            .detail(String.format("%s – %s", charge.getPeriodFrom(), charge.getPeriodTo()))
                            .amount(charge.getAmount().setScale(2, RoundingMode.HALF_UP))
                            .occurredAt(charge.getCreatedAt())
                            .businessDate(charge.getPeriodTo())
                            .status(charge.getVoidedAt() == null ? ACTIVE : WAIVED)
                            .countedInBalance(charge.getVoidedAt() == null)
                            .build());
                });
        adjustmentRepository.findByClientIdOrderByCreatedAtDesc(clientId)
                .forEach(adjustment -> transactions.add(adjustmentRow(adjustment)));
        markOpenDisputes(clientId, transactions);
        return transactions;
    }

    private static CreditTransactionResponse adjustmentRow(CreditAdjustment adjustment) {
        return CreditTransactionResponse.builder()
                .type(adjustment.getKind().name())
                .referenceId(adjustment.getId())
                .reference(adjustment.getReason())
                .detail(adjustment.getRefundMode() != null ? adjustment.getRefundMode().name() : null)
                .amount(adjustment.getAmount().setScale(2, RoundingMode.HALF_UP))
                .occurredAt(adjustment.getCreatedAt())
                .businessDate(adjustment.getAdjustmentDate())
                .status(ACTIVE)
                .countedInBalance(true)
                .linkedReferenceId(adjustment.getDebitId())
                .linkedReferenceType(adjustment.getDebitType() != null ? adjustment.getDebitType().name() : null)
                .build();
    }

    private void markOpenDisputes(Long clientId, List<CreditTransactionResponse> transactions) {
        Map<String, Long> disputeIdByDebit = disputeRepository
                .findByClientIdInAndStatus(List.of(clientId), CreditDisputeStatus.OPEN).stream()
                .collect(Collectors.toMap(dispute -> dispute.getDebitType().name() + ":" + dispute.getDebitId(),
                        CreditDispute::getId));
        if (disputeIdByDebit.isEmpty()) {
            return;
        }
        transactions.forEach(row -> row.setOpenDisputeId(disputeIdByDebit.get(row.getType() + ":" + row.getReferenceId())));
    }

    /** A reversed payment stays in the running balance until its reversal row takes it back out. */
    private static boolean countsInLedgerBalance(ExpenseApprovalStatus status) {
        return status == ExpenseApprovalStatus.APPROVED || status == ExpenseApprovalStatus.REVERSED;
    }

    /** The reversal of a payment, and its bounce charge as a separate debit when there is one. */
    private static List<CreditTransactionResponse> reversalRows(CreditPaymentReversal reversal, CreditPayment payment) {
        LocalDate paidAt = payment.getPaidAt();
        List<CreditTransactionResponse> rows = new ArrayList<>();
        Map<String, Object> reversalParams = new LinkedHashMap<>();
        reversalParams.put("paidAt", paidAt.toString());
        reversalParams.put("reason", reversal.getReason());
        rows.add(CreditTransactionResponse.builder()
                .type(PAYMENT_REVERSAL)
                .referenceId(reversal.getId())
                .reference("Payment of " + paidAt + " reversed: " + reversal.getReason())
                .referenceCode(ServerTextCodes.PAYMENT_REVERSAL_DETAILS)
                .referenceParams(reversalParams)
                .detail(payment.getPaymentMode().name())
                .amount(reversal.getAmount().setScale(2, RoundingMode.HALF_UP))
                .occurredAt(reversal.getCreatedAt())
                .businessDate(reversal.getReversalDate())
                .status(ACTIVE)
                .countedInBalance(true)
                .linkedReferenceId(payment.getId())
                .build());
        if (reversal.getBounceCharge() != null) {
            rows.add(CreditTransactionResponse.builder()
                    .type(BOUNCE_CHARGE)
                    .referenceId(reversal.getId())
                    .reference("Bounce charge for the payment of " + paidAt)
                    .referenceCode(ServerTextCodes.BOUNCE_CHARGE_DETAILS)
                    .referenceParams(Map.of("paidAt", paidAt.toString()))
                    .amount(reversal.getBounceCharge().setScale(2, RoundingMode.HALF_UP))
                    // Saved with the reversal; a microsecond later keeps it listed after the reversal in both orders.
                    .occurredAt(reversal.getCreatedAt().plusNanos(1_000))
                    .businessDate(reversal.getReversalDate())
                    .status(ACTIVE)
                    .countedInBalance(true)
                    .linkedReferenceId(payment.getId())
                    .build());
        }
        return rows;
    }

    /** Expects the rows oldest first; rows not counted in the balance carry the balance before them. */
    private static void applyRunningBalance(List<CreditTransactionResponse> transactions) {
        BigDecimal runningBalance = BigDecimal.ZERO;
        for (CreditTransactionResponse transaction : transactions) {
            if (!transaction.isCountedInBalance()) {
                transaction.setRunningBalance(runningBalance.setScale(2, RoundingMode.HALF_UP));
                continue;
            }
            if (CreditTransactionResponse.isCredit(transaction.getType())) {
                runningBalance = runningBalance.subtract(transaction.getAmount());
            } else {
                runningBalance = runningBalance.add(transaction.getAmount());
            }
            transaction.setRunningBalance(runningBalance.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private static CodedText counterSaleLabel(AncillarySale sale, AncillaryProduct product) {
        String productName = product != null ? AncillaryProductQueryService.buildDisplayName(product) : "Product";
        String label = productName + " × " + sale.getQuantityUnits();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("productName", productName);
        params.put("quantity", sale.getQuantityUnits());
        if (sale.getBillNo() == null || sale.getBillNo().isBlank()) {
            return new CodedText(ServerTextCodes.COUNTER_SALE_DETAILS, params, label);
        }
        params.put("billNo", sale.getBillNo());
        return new CodedText(ServerTextCodes.COUNTER_SALE_DETAILS_WITH_BILL, params,
                label + " (Bill " + sale.getBillNo() + ")");
    }

    public Map<Long, BigDecimal> depositHeldByClientId(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        return depositRepository.heldByClientIds(clientIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (BigDecimal) row[1]));
    }

    /** What an account has paid ahead: the part of a balance below zero. */
    public static BigDecimal advanceOf(BigDecimal outstanding) {
        return outstanding.signum() < 0 ? outstanding.negate().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private CreditClientResponse toClientResponse(CreditClient client,
                                                  Map<Long, BigDecimal> outstandingByClientId,
                                                  Map<Long, CreditInterestService.InterestBreakdown> breakdownMap,
                                                  Map<Long, List<CreditClient>> childrenByParentId,
                                                  Map<Long, String> nameById,
                                                  Map<Long, BigDecimal> depositHeldById) {
        boolean isParent = client.getParentClientId() == null && childrenByParentId.containsKey(client.getId());
        BigDecimal outstanding = outstandingByClientId.getOrDefault(client.getId(), BigDecimal.ZERO);
        CreditInterestService.InterestBreakdown breakdown = breakdownMap.getOrDefault(client.getId(), CreditInterestService.InterestBreakdown.ZERO);

        if (isParent) {
            List<CreditClient> children = childrenByParentId.get(client.getId());
            BigDecimal childOutstanding = children.stream()
                    .map(child -> outstandingByClientId.getOrDefault(child.getId(), BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            outstanding = outstanding.add(childOutstanding);

            CreditInterestService.InterestBreakdown childBreakdown = children.stream()
                    .map(child -> breakdownMap.getOrDefault(child.getId(), CreditInterestService.InterestBreakdown.ZERO))
                    .reduce(CreditInterestService.InterestBreakdown.ZERO, CreditInterestService.InterestBreakdown::add);
            breakdown = breakdown.add(childBreakdown);
        }

        return CreditClientResponse.builder()
                .id(client.getId())
                .pumpId(client.getPumpId())
                .name(client.getName())
                .phone(client.getPhone())
                .notes(client.getNotes())
                .creditLimit(client.getCreditLimit())
                .unlimited(client.isUnlimitedCredit())
                .outstandingBalance(outstanding.setScale(2, RoundingMode.HALF_UP))
                .outstandingInterest(breakdown.outstandingInterest())
                .totalInterestRecovered(breakdown.totalInterestRecovered())
                .monthlyInterestRate(client.getMonthlyInterestRate())
                .interestGraceDays(client.getInterestGraceDays())
                .createdAt(client.getCreatedAt())
                .parentClientId(client.getParentClientId())
                .parentClientName(client.getParentClientId() != null ? nameById.get(client.getParentClientId()) : null)
                .isParent(isParent)
                .advance(client.getParentClientId() == null ? advanceOf(outstanding) : BigDecimal.ZERO.setScale(2))
                .depositHeld(depositHeldById.getOrDefault(client.getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))
                .closedAt(client.getClosedAt())
                .closeReason(client.getCloseReason())
                .mergedIntoClientId(client.getMergedIntoClientId())
                .writtenOffOn(client.getWrittenOffOn())
                .build();
    }

    private Map<Long, String> loadUserNames(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
    }
}
