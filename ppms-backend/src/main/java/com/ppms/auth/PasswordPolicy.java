package com.ppms.auth;

public final class PasswordPolicy {

    public static final String REGEX =
            "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$";
    public static final String MESSAGE =
            "Password must be at least 8 characters and include 1 uppercase letter, 1 lowercase letter, 1 digit, and 1 symbol.";

    private PasswordPolicy() {
    }
}
