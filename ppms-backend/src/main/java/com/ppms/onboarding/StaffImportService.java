package com.ppms.onboarding;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserGender;
import com.ppms.user.UserLanguage;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Imports staff from the staff template. Each new member gets a random temporary password they must change at first
 * sign-in, as Add Staff does with the password the owner types; the passwords are returned once, never logged.
 * A phone number already working at this pump is skipped, so uploading the same file again adds nobody twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffImportService {

    static final String FULL_NAME = "full_name";
    static final String PHONE = "phone";
    static final String ROLE = "role";
    static final String GENDER = "gender";
    static final String EMPLOYEE_ID = "employee_id";
    static final String DATE_OF_JOINING = "date_of_joining";
    static final String NIGHT_SHIFT_CONSENT = "night_shift_consent";
    static final String ADDRESS = "address";
    static final String LANGUAGE = "language";
    static final String DAILY_RATE = "daily_rate";
    static final String DAY_HOURLY_RATE = "day_hourly_rate";
    static final String NIGHT_HOURLY_RATE = "night_hourly_rate";

    private static final List<UserRole> IMPORTABLE_ROLES =
            List.of(UserRole.OPERATOR, UserRole.MANAGER, UserRole.ADMIN, UserRole.ACCOUNTANT);
    private static final BigDecimal MIN_RATE = new BigDecimal("0.01");
    private static final BigDecimal MAX_RATE = new BigDecimal("100000");

    private final UserRepository userRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator passwordGenerator;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    private record CheckedRow(RowCheck check, String fullName, String phone, UserRole role,
                              UserGender gender, String employeeId, LocalDate dateOfJoining, boolean nightShiftConsent,
                              String address, String language, BigDecimal dailyRate, BigDecimal dayHourlyRate,
                              BigDecimal nightHourlyRate) {
    }

    @Transactional(readOnly = true)
    public ImportReport preview(Long pumpId, StaffImportRequest request, User currentUser) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_STAFF_ROWS);
        return ImportReport.of(checkRows(pumpId, request.rows(), currentUser).stream().map(CheckedRow::check).toList(),
                false, List.of());
    }

    @Transactional
    public ImportReport importRows(Long pumpId, StaffImportRequest request, User currentUser) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_STAFF_ROWS);
        pumpLocationRepository.findByIdForUpdate(pumpId);
        List<CheckedRow> rows = checkRows(pumpId, request.rows(), currentUser);
        List<RowCheck> checks = rows.stream().map(CheckedRow::check).toList();
        ImportLimits.requireNoErrors(ImportReport.of(checks, false, List.of()));

        List<CheckedRow> toCreate = rows.stream().filter(row -> row.check().isCreated()).toList();
        List<String> passwords = toCreate.stream().map(row -> passwordGenerator.generate()).toList();
        List<String> employeeIds = employeeIdsFor(pumpId, toCreate);
        List<User> users = new ArrayList<>();
        for (int i = 0; i < toCreate.size(); i++) {
            users.add(newUser(pumpId, toCreate.get(i), employeeIds.get(i), passwords.get(i)));
        }
        userRepository.saveAll(users);

        List<StaffCredential> credentials = new ArrayList<>();
        for (int i = 0; i < toCreate.size(); i++) {
            CheckedRow row = toCreate.get(i);
            credentials.add(new StaffCredential(row.check().rowNumber(), row.fullName(), row.phone(),
                    employeeIds.get(i), passwords.get(i)));
        }

        ImportReport report = ImportReport.of(checks, true, credentials);
        auditService.log(pumpId, AuditAction.STAFF_IMPORTED, "User", null,
                "Staff import: " + report.createCount() + " added, " + report.skipCount()
                        + " skipped as already at this pump; each must change the temporary password at first sign-in",
                currentUser);
        log.info("Staff imported: pump={}, created={}, skipped={}, by={}",
                pumpId, report.createCount(), report.skipCount(), currentUser.getId());
        return report;
    }

    private List<CheckedRow> checkRows(Long pumpId, List<StaffImportRequest.Row> rows, User currentUser) {
        LocalDate today = businessClock.today();
        List<CheckedRow> checked = rows.stream().map(row -> checkCells(row, today, currentUser)).toList();

        Set<String> phones = checked.stream().map(CheckedRow::phone).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> usersByPhone = phones.isEmpty() ? Map.of() : userRepository.findByPhoneNumberIn(phones).stream()
                .collect(Collectors.toMap(User::getPhoneNumber, Function.identity()));
        Set<String> employeeIds = checked.stream().map(CheckedRow::employeeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, User> usersByEmployeeId = employeeIds.isEmpty() ? Map.of()
                : userRepository.findByEmployeeIdIn(employeeIds).stream()
                        .collect(Collectors.toMap(User::getEmployeeId, Function.identity()));

        Map<String, Integer> firstRowByPhone = new HashMap<>();
        Map<String, Integer> firstRowByEmployeeId = new HashMap<>();
        for (CheckedRow row : checked) {
            RowCheck check = row.check();
            if (row.phone() != null) {
                Integer firstRow = firstRowByPhone.putIfAbsent(row.phone(), check.rowNumber());
                User existing = usersByPhone.get(row.phone());
                if (firstRow != null) {
                    check.error(PHONE, ErrorCodes.IMPORT_DUPLICATE_IN_FILE, Map.of("firstRow", firstRow));
                } else if (existing != null && pumpId.equals(existing.getAssignedPumpId())) {
                    check.skip(PHONE, ErrorCodes.IMPORT_ALREADY_EXISTS, Map.of());
                } else if (existing != null) {
                    check.error(PHONE, ErrorCodes.USER_PHONE_ALREADY_EXISTS);
                }
            }
            if (row.employeeId() != null && !check.isSkipped()) {
                Integer firstRow = firstRowByEmployeeId.putIfAbsent(row.employeeId(), check.rowNumber());
                if (firstRow != null) {
                    check.error(EMPLOYEE_ID, ErrorCodes.IMPORT_DUPLICATE_IN_FILE, Map.of("firstRow", firstRow));
                } else if (usersByEmployeeId.containsKey(row.employeeId())) {
                    check.error(EMPLOYEE_ID, ErrorCodes.EMPLOYEE_ID_TAKEN, Map.of("employeeId", row.employeeId()));
                }
            }
        }
        return checked;
    }

    private CheckedRow checkCells(StaffImportRequest.Row row, LocalDate today, User currentUser) {
        RowCheck check = new RowCheck(row.rowNumber());
        String fullName = CellReader.requiredText(check, FULL_NAME, row.fullName(), 100);
        String phone = CellReader.requiredPhone(check, PHONE, row.phone());
        UserRole role = CellReader.choice(check, ROLE, row.role(), true, IMPORTABLE_ROLES);
        if (role == UserRole.ADMIN && currentUser.getRole() == UserRole.ADMIN) {
            check.error(ROLE, ErrorCodes.ROLE_NOT_ASSIGNABLE,
                    Map.of("targetRoleCode", role.name(), "callerRoleCode", currentUser.getRole().name()));
        }
        UserGender gender = CellReader.choice(check, GENDER, row.gender(), true, List.of(UserGender.values()));
        String employeeId = CellReader.optionalText(check, EMPLOYEE_ID, row.employeeId(), 50);
        LocalDate dateOfJoining = CellReader.date(check, DATE_OF_JOINING, row.dateOfJoining(), today);
        boolean nightShiftConsent = CellReader.yesNo(check, NIGHT_SHIFT_CONSENT, row.nightShiftConsent());
        String address = CellReader.optionalText(check, ADDRESS, row.address(), 200);
        String language = CellReader.textChoice(check, LANGUAGE, row.language(), UserLanguage.PATTERN.split("\\|"));
        BigDecimal dailyRate = CellReader.decimal(check, DAILY_RATE, row.dailyRate(), false, MIN_RATE, MAX_RATE, 2);
        BigDecimal dayHourlyRate = CellReader.decimal(check, DAY_HOURLY_RATE, row.dayHourlyRate(), false, MIN_RATE, MAX_RATE, 2);
        BigDecimal nightHourlyRate = CellReader.decimal(check, NIGHT_HOURLY_RATE, row.nightHourlyRate(), false, MIN_RATE, MAX_RATE, 2);
        if (role != null) {
            checkRatesFitRole(check, role, row);
        }
        return new CheckedRow(check, fullName, phone, role, gender, employeeId, dateOfJoining, nightShiftConsent,
                address, language, dailyRate, dayHourlyRate, nightHourlyRate);
    }

    /** Operators are paid by the hour (day and night rate), other staff by the day, as on the pay rates form. */
    private static void checkRatesFitRole(RowCheck check, UserRole role, StaffImportRequest.Row row) {
        Map<String, Object> params = Map.of("roleCode", role.name());
        if (role == UserRole.OPERATOR) {
            if (CellReader.text(row.dailyRate()) != null) {
                check.error(DAILY_RATE, ErrorCodes.IMPORT_RATE_NOT_FOR_ROLE, params);
            }
            return;
        }
        if (CellReader.text(row.dayHourlyRate()) != null) {
            check.error(DAY_HOURLY_RATE, ErrorCodes.IMPORT_RATE_NOT_FOR_ROLE, params);
        }
        if (CellReader.text(row.nightHourlyRate()) != null) {
            check.error(NIGHT_HOURLY_RATE, ErrorCodes.IMPORT_RATE_NOT_FOR_ROLE, params);
        }
    }

    /** IDs left blank follow Add Staff's EMP{pumpId}{sequence} pattern, stepping past any already taken. */
    private List<String> employeeIdsFor(Long pumpId, List<CheckedRow> rows) {
        long sequence = userRepository.countByAssignedPumpId(pumpId);
        Set<String> used = new HashSet<>();
        rows.stream().map(CheckedRow::employeeId).filter(Objects::nonNull).forEach(used::add);
        List<String> ids = new ArrayList<>();
        for (CheckedRow row : rows) {
            if (row.employeeId() != null) {
                ids.add(row.employeeId());
                continue;
            }
            String candidate;
            do {
                sequence++;
                candidate = String.format("EMP%d%05d", pumpId, sequence);
            } while (used.contains(candidate) || userRepository.existsByEmployeeId(candidate));
            used.add(candidate);
            ids.add(candidate);
        }
        return ids;
    }

    private User newUser(Long pumpId, CheckedRow row, String employeeId, String temporaryPassword) {
        boolean isOperator = row.role() == UserRole.OPERATOR;
        return User.builder()
                .fullName(row.fullName())
                .phoneNumber(row.phone())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .gender(row.gender())
                .nightShiftConsent(isOperator && row.gender() == UserGender.FEMALE && row.nightShiftConsent())
                .role(row.role())
                .status(UserStatus.ACTIVE)
                .assignedPumpId(pumpId)
                .address(row.address())
                .employeeId(employeeId)
                .dateOfJoining(row.dateOfJoining() != null ? row.dateOfJoining() : businessClock.today())
                .mustChangePassword(true)
                .preferredLanguage(row.language() != null ? row.language() : UserLanguage.DEFAULT)
                .dailyRate(isOperator ? null : money(row.dailyRate()))
                .standardHourlyRate(isOperator ? money(row.dayHourlyRate()) : null)
                .shift1HourlyRate(isOperator ? money(row.nightHourlyRate()) : null)
                .build();
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
