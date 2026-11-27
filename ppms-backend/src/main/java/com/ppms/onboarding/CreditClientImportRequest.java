package com.ppms.onboarding;

import java.util.List;

/** The rows of a credit client import file; a row naming a parent becomes that client's sub-account. */
public record CreditClientImportRequest(List<Row> rows) {

    public record Row(int rowNumber, String name, String phone, String parentName, String creditLimit,
                      String openingBalance, String openingBalanceDate, String gstin, String billingAddress,
                      String notes) {
    }
}
