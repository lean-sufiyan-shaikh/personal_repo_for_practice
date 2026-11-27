package com.ppms.onboarding;

/** The sign-in details of one imported staff member, returned once so the owner can hand them over. */
public record StaffCredential(int rowNumber, String fullName, String phoneNumber, String employeeId,
                              String temporaryPassword) {

    @Override
    public String toString() {
        return "StaffCredential[rowNumber=" + rowNumber + ", employeeId=" + employeeId + "]";
    }
}
