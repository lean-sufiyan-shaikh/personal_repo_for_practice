package com.ppms.credit;

import com.ppms.fleet.VehicleUsageGroup;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the printable statement shows. For a parent account the rows and the outstanding cover its whole group,
 * each row carrying the name of the account it belongs to; otherwise they cover the client alone.
 *
 * @param subAccountNames empty unless the client is a parent account
 * @param vehicleUsage    the period's credit grouped by vehicle, unknown vehicles last; empty when the statement
 *                        shows no vehicles
 * @param depositHeld     security deposit held for the accounts covered; never part of the outstanding
 */
public record CreditStatement(List<CreditTransactionResponse> transactions, BigDecimal outstanding,
                              List<String> subAccountNames, List<VehicleUsageGroup> vehicleUsage,
                              BigDecimal depositHeld) {

    public boolean coversSubAccounts() {
        return !subAccountNames.isEmpty();
    }
}
