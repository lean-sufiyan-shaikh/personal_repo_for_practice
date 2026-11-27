package com.ppms.onboarding;

import java.util.List;

/** The rows of a staff import file as the import screen read them; every cell is sent as text and checked here. */
public record StaffImportRequest(List<Row> rows) {

    public record Row(int rowNumber, String fullName, String phone, String role, String gender, String employeeId,
                      String dateOfJoining, String nightShiftConsent, String address, String language,
                      String dailyRate, String dayHourlyRate, String nightHourlyRate) {
    }
}
