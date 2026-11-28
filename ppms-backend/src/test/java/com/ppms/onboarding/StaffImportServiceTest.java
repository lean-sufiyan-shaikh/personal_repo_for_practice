package com.ppms.onboarding;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffImportServiceTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Mock private UserRepository userRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TemporaryPasswordGenerator passwordGenerator;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;

    @InjectMocks private StaffImportService service;

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();

    @BeforeEach
    void setUp() {
        lenient().when(businessClock.today()).thenReturn(TODAY);
    }

    @Test
    void previewReportsEachProblemAgainstItsCellAndCreatesNothing() {
        ImportReport report = service.preview(PUMP_ID, request(
                row(2, "", "12345", "DRIVER", "male", null, null, null, null),
                row(3, "Asha", "9876500001", "OPERATOR", "FEMALE", null, "2099-01-01", "500", null),
                row(4, "Valid Person", "9876500002", "ACCOUNTANT", "OTHER", null, null, null, null)), owner);

        assertThat(report.imported()).isFalse();
        assertThat(report.errorCount()).isEqualTo(2);
        assertThat(report.createCount()).isEqualTo(1);
        assertThat(issues(report, 0)).containsExactlyInAnyOrder(
                "full_name:IMPORT_VALUE_REQUIRED", "phone:IMPORT_PHONE_INVALID", "role:IMPORT_CHOICE_INVALID");
        assertThat(issues(report, 1)).containsExactlyInAnyOrder(
                "date_of_joining:IMPORT_DATE_IN_FUTURE", "daily_rate:IMPORT_RATE_NOT_FOR_ROLE");
        verify(userRepository, never()).saveAll(any());
    }

    @Test
    void hourlyRatesAreRefusedForStaffPaidByTheDay() {
        ImportReport report = service.preview(PUMP_ID, request(
                row(2, "Meena", "9876500003", "MANAGER", "FEMALE", null, null, null, "60")), owner);

        assertThat(issues(report, 0)).containsExactly("day_hourly_rate:IMPORT_RATE_NOT_FOR_ROLE");
    }

    @Test
    void duplicatesInTheFileAndPhonesOfOtherPumpsAreErrorsWhileStaffAlreadyHereAreSkipped() {
        User alreadyHere = User.builder().id(20L).phoneNumber("9876500011").assignedPumpId(PUMP_ID).build();
        User elsewhere = User.builder().id(21L).phoneNumber("9876500012").assignedPumpId(99L).build();
        User takenId = User.builder().id(22L).employeeId("EMP700001").assignedPumpId(PUMP_ID).build();
        when(userRepository.findByPhoneNumberIn(anyCollection())).thenReturn(List.of(alreadyHere, elsewhere));
        when(userRepository.findByEmployeeIdIn(anyCollection())).thenReturn(List.of(takenId));

        ImportReport report = service.preview(PUMP_ID, request(
                row(2, "First", "9876500010", "OPERATOR", "MALE", null, null, null, null),
                row(3, "Second", "9876500010", "OPERATOR", "MALE", null, null, null, null),
                row(4, "Already here", "9876500011", "OPERATOR", "MALE", null, null, null, null),
                row(5, "Works elsewhere", "9876500012", "OPERATOR", "MALE", null, null, null, null),
                row(6, "Taken id", "9876500013", "OPERATOR", "MALE", "EMP700001", null, null, null)), owner);

        assertThat(report.rows()).extracting(ImportRowResult::outcome).containsExactly(
                RowOutcome.CREATE, RowOutcome.ERROR, RowOutcome.SKIP, RowOutcome.ERROR, RowOutcome.ERROR);
        assertThat(report.rows().get(1).issues().get(0).params()).containsEntry("firstRow", 2);
        assertThat(issues(report, 2)).containsExactly("phone:IMPORT_ALREADY_EXISTS");
        assertThat(issues(report, 3)).containsExactly("phone:USER_PHONE_ALREADY_EXISTS");
        assertThat(issues(report, 4)).containsExactly("employee_id:EMPLOYEE_ID_TAKEN");
    }

    @Test
    void adminCannotAddAdmins() {
        User admin = User.builder().id(2L).role(UserRole.ADMIN).assignedPumpId(PUMP_ID).build();

        ImportReport report = service.preview(PUMP_ID, request(
                row(2, "Ravi", "9876500020", "ADMIN", "MALE", null, null, null, null)), admin);

        assertThat(issues(report, 0)).containsExactly("role:ROLE_NOT_ASSIGNABLE");
    }

    @Test
    void fileWithAnyProblemImportsNothing() {
        StaffImportRequest file = request(
                row(2, "Good", "9876500030", "OPERATOR", "MALE", null, null, null, null),
                row(3, "Bad", "abc", "OPERATOR", "MALE", null, null, null, null));

        assertThatThrownBy(() -> service.importRows(PUMP_ID, file, owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(refusal -> {
                    assertThat(((BusinessException) refusal).getCode()).isEqualTo(ErrorCodes.IMPORT_FILE_HAS_ERRORS);
                    assertThat(((BusinessException) refusal).getParams()).containsEntry("errorCount", 1);
                });
        verify(userRepository, never()).saveAll(any());
    }

    @Test
    void fileWithMoreRowsThanTheStaffLimitOrNoRowsIsRefused() {
        List<StaffImportRequest.Row> tooMany = new ArrayList<>();
        for (int i = 0; i <= ImportLimits.MAX_STAFF_ROWS; i++) {
            tooMany.add(row(i + 2, "Person " + i, "98765" + String.format("%05d", i), "OPERATOR", "MALE",
                    null, null, null, null));
        }

        assertThatThrownBy(() -> service.preview(PUMP_ID, new StaffImportRequest(tooMany), owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(refusal -> {
                    assertThat(((BusinessException) refusal).getCode()).isEqualTo(ErrorCodes.IMPORT_TOO_MANY_ROWS);
                    assertThat(((BusinessException) refusal).getParams()).containsEntry("maxRows", ImportLimits.MAX_STAFF_ROWS);
                });
        assertThatThrownBy(() -> service.preview(PUMP_ID, new StaffImportRequest(List.of()), owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.IMPORT_NO_ROWS);
    }

    private static StaffImportRequest request(StaffImportRequest.Row... rows) {
        return new StaffImportRequest(List.of(rows));
    }

    private static StaffImportRequest.Row row(int rowNumber, String name, String phone, String role, String gender,
                                              String employeeId, String dateOfJoining, String dailyRate,
                                              String dayHourlyRate) {
        return new StaffImportRequest.Row(rowNumber, name, phone, role, gender, employeeId, dateOfJoining, null, null,
                null, dailyRate, dayHourlyRate, null);
    }

    private static List<String> issues(ImportReport report, int rowIndex) {
        return report.rows().get(rowIndex).issues().stream()
                .map(issue -> issue.column() + ":" + issue.code())
                .toList();
    }
}
