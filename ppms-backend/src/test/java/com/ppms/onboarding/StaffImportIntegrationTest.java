package com.ppms.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestAddresses;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffImportIntegrationTest extends ApiIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void validFileCreatesStaffWhoMustChangeTheirOneTimePassword() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        String operatorPhone = freePhone();
        String managerPhone = freePhone();

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/staff", setup.pumpId()), setup.owner(), rows(
                row(2, "Ravi Kumar", operatorPhone, "operator", "male", Map.of("day_hourly_rate", "60", "night_hourly_rate", "75")),
                row(3, "Meena Patil", managerPhone, "MANAGER", "FEMALE", Map.of("daily_rate", "950.50", "language", "mr"))))
                .andExpect(status().isOk()));

        assertThat(report.get("imported").asBoolean()).isTrue();
        assertThat(report.get("createCount").asInt()).isEqualTo(2);
        assertThat(report.get("credentials")).hasSize(2);
        JsonNode operatorCredential = report.get("credentials").get(0);
        assertThat(operatorCredential.get("phoneNumber").asText()).isEqualTo(operatorPhone);
        assertThat(operatorCredential.get("employeeId").asText()).startsWith("EMP" + setup.pumpId());

        User operator = userRepository.findByPhoneNumber(operatorPhone).orElseThrow();
        assertThat(operator.getRole()).isEqualTo(UserRole.OPERATOR);
        assertThat(operator.getAssignedPumpId()).isEqualTo(setup.pumpId());
        assertThat(operator.isMustChangePassword()).isTrue();
        assertThat(operator.getStandardHourlyRate()).isEqualByComparingTo("60");
        assertThat(operator.getShift1HourlyRate()).isEqualByComparingTo("75");
        String temporaryPassword = operatorCredential.get("temporaryPassword").asText();
        assertThat(operator.getPasswordHash()).isNotEqualTo(temporaryPassword);
        assertThat(passwordEncoder.matches(temporaryPassword, operator.getPasswordHash())).isTrue();
        User manager = userRepository.findByPhoneNumber(managerPhone).orElseThrow();
        assertThat(manager.getDailyRate()).isEqualByComparingTo(new BigDecimal("950.50"));
        assertThat(manager.getPreferredLanguage()).isEqualTo("mr");

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(TestAddresses.randomPrivateAddress());
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phoneNumber", operatorPhone, "password", temporaryPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        String auditText = jdbcTemplate.queryForObject(
                "SELECT description FROM audit_logs WHERE pump_id = ? AND action = 'STAFF_IMPORTED'", String.class, setup.pumpId());
        assertThat(auditText).contains("2 added").doesNotContain(temporaryPassword).doesNotContain(operatorPhone);
    }

    @Test
    void previewReportsEachProblemAgainstItsCellAndCreatesNothing() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        String phone = freePhone();

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/staff/preview", setup.pumpId()), setup.owner(), rows(
                row(2, "", "12345", "DRIVER", "male", Map.of()),
                row(3, "Asha", phone, "OPERATOR", "FEMALE", Map.of("daily_rate", "500", "date_of_joining", "2099-01-01")),
                row(4, "Valid Person", freePhone(), "ACCOUNTANT", "OTHER", Map.of())))
                .andExpect(status().isOk()));

        assertThat(report.get("imported").asBoolean()).isFalse();
        assertThat(report.get("errorCount").asInt()).isEqualTo(2);
        assertThat(report.get("createCount").asInt()).isEqualTo(1);
        assertThat(issues(report, 0)).containsExactlyInAnyOrder(
                "full_name:IMPORT_VALUE_REQUIRED", "phone:IMPORT_PHONE_INVALID", "role:IMPORT_CHOICE_INVALID");
        assertThat(issues(report, 1)).containsExactlyInAnyOrder(
                "date_of_joining:IMPORT_DATE_IN_FUTURE", "daily_rate:IMPORT_RATE_NOT_FOR_ROLE");
        assertThat(userRepository.findByPhoneNumber(phone)).isEmpty();
    }

    @Test
    void duplicatesInTheFileAndPhonesOfOtherPumpsAreErrorsWhileStaffAlreadyHereAreSkipped() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        PumpSetup otherPump = createPumpWithStock("0");
        String repeated = freePhone();

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/staff/preview", setup.pumpId()), setup.owner(), rows(
                row(2, "First", repeated, "OPERATOR", "MALE", Map.of()),
                row(3, "Second", repeated, "OPERATOR", "MALE", Map.of()),
                row(4, "Already here", setup.operator().getPhoneNumber(), "OPERATOR", "MALE", Map.of()),
                row(5, "Works elsewhere", otherPump.operator().getPhoneNumber(), "OPERATOR", "MALE", Map.of()),
                row(6, "Taken id", freePhone(), "OPERATOR", "MALE", Map.of("employee_id", setup.manager().getEmployeeId()))))
                .andExpect(status().isOk()));

        assertThat(outcomes(report)).containsExactly("CREATE", "ERROR", "SKIP", "ERROR", "ERROR");
        assertThat(report.get("rows").get(1).get("issues").get(0).get("params").get("firstRow").asInt()).isEqualTo(2);
        assertThat(issues(report, 2)).containsExactly("phone:IMPORT_ALREADY_EXISTS");
        assertThat(issues(report, 3)).containsExactly("phone:USER_PHONE_ALREADY_EXISTS");
        assertThat(issues(report, 4)).containsExactly("employee_id:EMPLOYEE_ID_TAKEN");
    }

    @Test
    void uploadingTheSameFileTwiceAddsNobodyTwice() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        Map<String, Object> file = rows(row(2, "Ravi", freePhone(), "OPERATOR", "MALE", Map.of()),
                row(3, "Sita", freePhone(), "ACCOUNTANT", "FEMALE", Map.of()));
        perform(post("/api/pumps/{pumpId}/imports/staff", setup.pumpId()), setup.owner(), file).andExpect(status().isOk());
        long staffAfterFirst = userRepository.countByAssignedPumpId(setup.pumpId());

        JsonNode second = json(perform(post("/api/pumps/{pumpId}/imports/staff", setup.pumpId()), setup.owner(), file)
                .andExpect(status().isOk()));

        assertThat(second.get("createCount").asInt()).isZero();
        assertThat(second.get("skipCount").asInt()).isEqualTo(2);
        assertThat(second.get("credentials")).isEmpty();
        assertThat(userRepository.countByAssignedPumpId(setup.pumpId())).isEqualTo(staffAfterFirst);
    }

    @Test
    void fileWithAnyProblemImportsNothing() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        String goodPhone = freePhone();

        perform(post("/api/pumps/{pumpId}/imports/staff", setup.pumpId()), setup.owner(), rows(
                row(2, "Good", goodPhone, "OPERATOR", "MALE", Map.of()),
                row(3, "Bad", "abc", "OPERATOR", "MALE", Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_HAS_ERRORS"))
                .andExpect(jsonPath("$.params.errorCount").value(1));

        assertThat(userRepository.findByPhoneNumber(goodPhone)).isEmpty();
    }

    @Test
    void fileLimitsAreEnforced() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= ImportLimits.MAX_STAFF_ROWS; i++) {
            tooMany.add(row(i + 2, "Person " + i, freePhone(), "OPERATOR", "MALE", Map.of()));
        }

        perform(post("/api/pumps/{pumpId}/imports/staff/preview", setup.pumpId()), setup.owner(), Map.of("rows", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_TOO_MANY_ROWS"))
                .andExpect(jsonPath("$.params.maxRows").value(ImportLimits.MAX_STAFF_ROWS));
        perform(post("/api/pumps/{pumpId}/imports/staff/preview", setup.pumpId()), setup.owner(), Map.of("rows", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_NO_ROWS"));
    }

    @Test
    void onlyOwnersAndAdminsOfThePumpImportAndAnAdminCannotAddAdmins() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        PumpSetup otherPump = createPumpWithStock("0");
        Map<String, Object> file = rows(row(2, "Ravi", freePhone(), "ADMIN", "MALE", Map.of()));

        perform(post("/api/pumps/{pumpId}/imports/staff", setup.pumpId()), setup.manager(), file).andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/imports/staff", setup.pumpId()), setup.accountant(), file).andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/imports/staff", setup.pumpId()), otherPump.owner(), file).andExpect(status().isForbidden());

        JsonNode adminPreview = json(perform(post("/api/pumps/{pumpId}/imports/staff/preview", setup.pumpId()), setup.admin(), file)
                .andExpect(status().isOk()));
        assertThat(issues(adminPreview, 0)).containsExactly("role:ROLE_NOT_ASSIGNABLE");
    }

    @SafeVarargs
    static Map<String, Object> rows(Map<String, Object>... rows) {
        return Map.of("rows", List.of(rows));
    }

    private static Map<String, Object> row(int rowNumber, String name, String phone, String role, String gender,
                                           Map<String, String> extra) {
        Map<String, Object> row = new HashMap<>();
        row.put("rowNumber", rowNumber);
        row.put("fullName", name);
        row.put("phone", phone);
        row.put("role", role);
        row.put("gender", gender);
        row.put("employeeId", extra.get("employee_id"));
        row.put("dateOfJoining", extra.get("date_of_joining"));
        row.put("language", extra.get("language"));
        row.put("dailyRate", extra.get("daily_rate"));
        row.put("dayHourlyRate", extra.get("day_hourly_rate"));
        row.put("nightHourlyRate", extra.get("night_hourly_rate"));
        return row;
    }

    static List<String> issues(JsonNode report, int rowIndex) {
        List<String> issues = new ArrayList<>();
        report.get("rows").get(rowIndex).get("issues")
                .forEach(issue -> issues.add(issue.get("column").asText() + ":" + issue.get("code").asText()));
        return issues;
    }

    static List<String> outcomes(JsonNode report) {
        List<String> outcomes = new ArrayList<>();
        report.get("rows").forEach(row -> outcomes.add(row.get("outcome").asText()));
        return outcomes;
    }

    static String freePhone() {
        return Long.toString(5_000_000_000L + ThreadLocalRandom.current().nextLong(999_999_999L));
    }
}
