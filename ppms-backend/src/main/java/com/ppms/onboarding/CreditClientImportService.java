package com.ppms.onboarding;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditOpeningBalance;
import com.ppms.credit.CreditOpeningBalanceRepository;
import com.ppms.invoice.GstFields;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Imports credit clients with the rules of the Add Client form: a root name is unique in the pump, a sub-account name
 * under its parent, sub-accounts are one level deep, and a phone may be shared only inside one client group.
 * A client that already exists is skipped (with its opening balance), so uploading the same file again adds nothing.
 * A main client's credit limit is an amount (0 allows no credit) or the word "unlimited"; left blank it takes the
 * pump's default limit, and when that default is 0 the cell must be filled so no client is cut off by accident.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditClientImportService {

    static final String NAME = "name";
    static final String PHONE = "phone";
    static final String PARENT_NAME = "parent_name";
    static final String CREDIT_LIMIT = "credit_limit";
    static final String OPENING_BALANCE = "opening_balance";
    static final String OPENING_BALANCE_DATE = "opening_balance_date";
    static final String GSTIN = "gstin";
    static final String BILLING_ADDRESS = "billing_address";
    static final String NOTES = "notes";

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
    private static final String UNLIMITED = "unlimited";

    private final CreditClientRepository clientRepository;
    private final CreditOpeningBalanceRepository openingBalanceRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    private record CheckedRow(RowCheck check, String name, String phone, String parentName, BigDecimal creditLimit,
                              boolean unlimitedCredit, BigDecimal openingBalance, LocalDate openingBalanceDate, String gstin,
                              String billingAddress, String notes) {

        boolean isSubAccount() {
            return parentName != null;
        }

        String groupKey() {
            return CellReader.lowerCaseKey(isSubAccount() ? parentName : name);
        }
    }

    /** The clients already at the pump, looked up by name and phone. */
    private record ExistingClients(Map<String, CreditClient> rootsByName, Map<String, CreditClient> subsByParentAndName,
                                   Map<String, List<Long>> groupIdsByPhone, List<String> subAccountNames) {

        static ExistingClients of(List<CreditClient> clients) {
            Map<String, CreditClient> roots = new HashMap<>();
            Map<String, CreditClient> subs = new HashMap<>();
            Map<String, List<Long>> groupIdsByPhone = new HashMap<>();
            List<String> subNames = new ArrayList<>();
            for (CreditClient client : clients) {
                if (client.getParentClientId() == null) {
                    roots.put(CellReader.lowerCaseKey(client.getName()), client);
                } else {
                    subs.put(client.getParentClientId() + "|" + CellReader.lowerCaseKey(client.getName()), client);
                    subNames.add(CellReader.lowerCaseKey(client.getName()));
                }
                if (client.getPhone() != null) {
                    groupIdsByPhone.computeIfAbsent(client.getPhone(), phone -> new ArrayList<>())
                            .add(client.getParentClientId() != null ? client.getParentClientId() : client.getId());
                }
            }
            return new ExistingClients(roots, subs, groupIdsByPhone, subNames);
        }

        boolean phoneUsedOutsideGroup(String phone, Long groupRootId) {
            return groupIdsByPhone.getOrDefault(phone, List.of()).stream().anyMatch(id -> !id.equals(groupRootId));
        }
    }

    @Transactional(readOnly = true)
    public ImportReport preview(Long pumpId, CreditClientImportRequest request) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_ROWS);
        return ImportReport.of(checkRows(pumpId, request.rows()).stream().map(CheckedRow::check).toList(), false, List.of());
    }

    @Transactional
    public ImportReport importRows(Long pumpId, CreditClientImportRequest request, User currentUser) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_ROWS);
        pumpLocationRepository.findByIdForUpdate(pumpId);
        List<CheckedRow> rows = checkRows(pumpId, request.rows());
        List<RowCheck> checks = rows.stream().map(CheckedRow::check).toList();
        ImportLimits.requireNoErrors(ImportReport.of(checks, false, List.of()));

        ExistingClients existing = ExistingClients.of(clientRepository.findByPumpIdOrderByNameAsc(pumpId));
        List<CheckedRow> roots = rows.stream().filter(row -> row.check().isCreated() && !row.isSubAccount()).toList();
        List<CheckedRow> subs = rows.stream().filter(row -> row.check().isCreated() && row.isSubAccount()).toList();

        List<CreditClient> savedRoots = clientRepository.saveAll(roots.stream()
                .map(row -> newClient(pumpId, row, null)).toList());
        Map<String, Long> rootIdByName = new HashMap<>();
        existing.rootsByName().forEach((name, client) -> rootIdByName.put(name, client.getId()));
        savedRoots.forEach(client -> rootIdByName.put(CellReader.lowerCaseKey(client.getName()), client.getId()));
        List<CreditClient> savedSubs = clientRepository.saveAll(subs.stream()
                .map(row -> newClient(pumpId, row, rootIdByName.get(row.groupKey()))).toList());

        List<CreditOpeningBalance> balances = new ArrayList<>();
        addOpeningBalances(pumpId, roots, savedRoots, currentUser, balances);
        addOpeningBalances(pumpId, subs, savedSubs, currentUser, balances);
        openingBalanceRepository.saveAll(balances);

        BigDecimal openingTotal = balances.stream().map(CreditOpeningBalance::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        ImportReport report = ImportReport.of(checks, true, List.of());
        auditService.log(pumpId, AuditAction.CREDIT_CLIENTS_IMPORTED, "CreditClient", null,
                "Credit client import: " + report.createCount() + " added (" + savedSubs.size() + " sub-accounts), "
                        + report.skipCount() + " skipped as already present; " + balances.size()
                        + " opening balance(s) totalling ₹" + openingTotal.toPlainString(),
                currentUser);
        log.info("Credit clients imported: pump={}, created={}, skipped={}, openingBalances={}, by={}",
                pumpId, report.createCount(), report.skipCount(), balances.size(), currentUser.getId());
        return report;
    }

    private void addOpeningBalances(Long pumpId, List<CheckedRow> rows, List<CreditClient> saved, User currentUser,
                                    List<CreditOpeningBalance> balances) {
        for (int i = 0; i < rows.size(); i++) {
            CheckedRow row = rows.get(i);
            if (row.openingBalance() == null || row.openingBalance().signum() == 0) {
                continue;
            }
            balances.add(CreditOpeningBalance.builder()
                    .pumpId(pumpId)
                    .clientId(saved.get(i).getId())
                    .amount(row.openingBalance().setScale(2, RoundingMode.HALF_UP))
                    .asOfDate(row.openingBalanceDate() != null ? row.openingBalanceDate() : businessClock.today())
                    .createdByUserId(currentUser.getId())
                    .build());
        }
    }

    private static CreditClient newClient(Long pumpId, CheckedRow row, Long parentClientId) {
        return CreditClient.builder()
                .pumpId(pumpId)
                .name(row.name())
                .phone(row.phone())
                .notes(row.notes())
                .creditLimit(row.creditLimit() != null ? row.creditLimit().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .unlimitedCredit(row.unlimitedCredit())
                .parentClientId(parentClientId)
                .gstin(row.gstin())
                .billingAddress(row.billingAddress())
                .build();
    }

    private List<CheckedRow> checkRows(Long pumpId, List<CreditClientImportRequest.Row> rows) {
        LocalDate today = businessClock.today();
        BigDecimal defaultLimit = pumpLocationRepository.findById(pumpId)
                .map(PumpLocation::getDefaultCreditLimit)
                .orElse(BigDecimal.ZERO);
        List<CheckedRow> checked = rows.stream().map(row -> checkCells(row, today, defaultLimit)).toList();
        ExistingClients existing = ExistingClients.of(clientRepository.findByPumpIdOrderByNameAsc(pumpId));

        Map<String, CheckedRow> rootRowsByName = new HashMap<>();
        for (CheckedRow row : checked) {
            if (row.name() != null && !row.isSubAccount()) {
                checkRoot(row, rootRowsByName, existing);
            }
        }
        Map<String, Integer> firstRowBySubKey = new HashMap<>();
        List<String> subRowNames = checked.stream().filter(CheckedRow::isSubAccount).map(CheckedRow::name)
                .filter(Objects::nonNull).map(CellReader::lowerCaseKey).toList();
        for (CheckedRow row : checked) {
            if (row.name() != null && row.isSubAccount()) {
                checkSubAccount(row, rootRowsByName, firstRowBySubKey, subRowNames, existing);
            }
        }
        checkPhonesStayInOneGroup(checked);
        return checked;
    }

    private static void checkRoot(CheckedRow row, Map<String, CheckedRow> rootRowsByName, ExistingClients existing) {
        RowCheck check = row.check();
        String key = CellReader.lowerCaseKey(row.name());
        CheckedRow first = rootRowsByName.putIfAbsent(key, row);
        if (first != null) {
            check.error(NAME, ErrorCodes.IMPORT_DUPLICATE_IN_FILE, Map.of("firstRow", first.check().rowNumber()));
            return;
        }
        if (existing.rootsByName().containsKey(key)) {
            check.skip(NAME, ErrorCodes.IMPORT_ALREADY_EXISTS, Map.of());
            return;
        }
        if (row.phone() != null && existing.groupIdsByPhone().containsKey(row.phone())) {
            check.error(PHONE, ErrorCodes.CREDIT_CLIENT_PHONE_TAKEN);
        }
    }

    private static void checkSubAccount(CheckedRow row, Map<String, CheckedRow> rootRowsByName,
                                        Map<String, Integer> firstRowBySubKey, List<String> subRowNames,
                                        ExistingClients existing) {
        RowCheck check = row.check();
        String parentKey = row.groupKey();
        CreditClient existingParent = existing.rootsByName().get(parentKey);
        CheckedRow parentRow = rootRowsByName.get(parentKey);
        if (existingParent == null && parentRow == null) {
            boolean parentIsSubAccount = existing.subAccountNames().contains(parentKey) || subRowNames.contains(parentKey);
            check.error(PARENT_NAME, parentIsSubAccount
                    ? ErrorCodes.CREDIT_SUB_ACCOUNT_NESTING_NOT_ALLOWED
                    : ErrorCodes.IMPORT_PARENT_NOT_FOUND, Map.of("parentName", row.parentName()));
            return;
        }
        if (existingParent == null && parentRow.check().hasErrors()) {
            check.error(PARENT_NAME, ErrorCodes.IMPORT_PARENT_ROW_HAS_ERRORS,
                    Map.of("parentRow", parentRow.check().rowNumber()));
            return;
        }
        String subKey = parentKey + "|" + CellReader.lowerCaseKey(row.name());
        Integer firstRow = firstRowBySubKey.putIfAbsent(subKey, check.rowNumber());
        if (firstRow != null) {
            check.error(NAME, ErrorCodes.IMPORT_DUPLICATE_IN_FILE, Map.of("firstRow", firstRow));
            return;
        }
        if (existingParent != null
                && existing.subsByParentAndName().containsKey(existingParent.getId() + "|" + CellReader.lowerCaseKey(row.name()))) {
            check.skip(NAME, ErrorCodes.IMPORT_ALREADY_EXISTS, Map.of());
            return;
        }
        if (row.phone() == null) {
            return;
        }
        boolean phoneTaken = existingParent != null
                ? existing.phoneUsedOutsideGroup(row.phone(), existingParent.getId())
                : existing.groupIdsByPhone().containsKey(row.phone());
        if (phoneTaken) {
            check.error(PHONE, ErrorCodes.CREDIT_CLIENT_PHONE_TAKEN);
        }
    }

    /** Inside the file too, rows of different client groups may not share a phone number. */
    private static void checkPhonesStayInOneGroup(List<CheckedRow> rows) {
        Map<String, CheckedRow> firstRowByPhone = new HashMap<>();
        for (CheckedRow row : rows) {
            if (row.phone() == null || row.name() == null || row.check().isSkipped()) {
                continue;
            }
            CheckedRow first = firstRowByPhone.putIfAbsent(row.phone(), row);
            if (first != null && !Objects.equals(first.groupKey(), row.groupKey())) {
                row.check().error(PHONE, ErrorCodes.IMPORT_PHONE_IN_OTHER_GROUP, Map.of("firstRow", first.check().rowNumber()));
            }
        }
    }

    private CheckedRow checkCells(CreditClientImportRequest.Row row, LocalDate today, BigDecimal defaultLimit) {
        RowCheck check = new RowCheck(row.rowNumber());
        String name = CellReader.requiredName(check, NAME, row.name(), 100);
        String phone = CellReader.requiredPhone(check, PHONE, row.phone());
        String parentName = CellReader.optionalText(check, PARENT_NAME, row.parentName(), 100);
        String limitCell = CellReader.text(row.creditLimit());
        boolean unlimitedCredit = UNLIMITED.equalsIgnoreCase(limitCell);
        BigDecimal creditLimit = unlimitedCredit ? null
                : CellReader.decimal(check, CREDIT_LIMIT, row.creditLimit(), false, BigDecimal.ZERO, MAX_AMOUNT, 2);
        if (parentName != null && limitCell != null) {
            check.error(CREDIT_LIMIT, ErrorCodes.IMPORT_CREDIT_LIMIT_ON_SUB_ACCOUNT);
        } else if (parentName == null && limitCell == null) {
            if (defaultLimit.signum() > 0) {
                creditLimit = defaultLimit;
            } else {
                check.error(CREDIT_LIMIT, ErrorCodes.IMPORT_CREDIT_LIMIT_REQUIRED);
            }
        }
        BigDecimal openingBalance = CellReader.decimal(check, OPENING_BALANCE, row.openingBalance(), false, BigDecimal.ZERO, MAX_AMOUNT, 2);
        LocalDate openingBalanceDate = CellReader.date(check, OPENING_BALANCE_DATE, row.openingBalanceDate(), today);
        String gstin = readGstin(check, row.gstin());
        String billingAddress = CellReader.optionalText(check, BILLING_ADDRESS, row.billingAddress(), 500);
        String notes = CellReader.optionalText(check, NOTES, row.notes(), 500);
        return new CheckedRow(check, name, phone, parentName, creditLimit, unlimitedCredit, openingBalance,
                openingBalanceDate, gstin,
                billingAddress, notes);
    }

    /** The client form's own GSTIN check; its refusal becomes a problem on this cell rather than failing the file. */
    private static String readGstin(RowCheck check, String raw) {
        try {
            return GstFields.normaliseGstin(raw);
        } catch (BusinessException e) {
            check.error(GSTIN, e.getCode());
            return null;
        }
    }
}
