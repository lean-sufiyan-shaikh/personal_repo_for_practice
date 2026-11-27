package com.ppms.report;

import com.ppms.common.text.CodedText;
import com.ppms.common.text.ServerTextCodes;
import com.ppms.fuel.FuelType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Why a saved balance sheet went out of date, in English and as a code with params for translated screens. */
public final class StaleReasons {

    private static final String AFTER_GENERATED = " after this sheet was generated.";

    private StaleReasons() {
    }

    public static CodedText drainRecorded(String litres) {
        return CodedText.of(ServerTextCodes.STALE_DRAIN_RECORDED, "litres", litres,
                "A drain of " + litres + " L was recorded" + AFTER_GENERATED);
    }

    public static CodedText shiftBackfilled(Long shiftId) {
        return CodedText.of(ServerTextCodes.STALE_SHIFT_BACKFILLED, "shiftId", shiftId,
                "Shift #" + shiftId + " was backfilled" + AFTER_GENERATED);
    }

    public static CodedText shiftClosed(Long shiftId) {
        return CodedText.of(ServerTextCodes.STALE_SHIFT_CLOSED, "shiftId", shiftId,
                "Shift #" + shiftId + " was closed" + AFTER_GENERATED);
    }

    public static CodedText cashRecovered(Long shiftId) {
        return CodedText.of(ServerTextCodes.STALE_CASH_RECOVERED, "shiftId", shiftId,
                "Cash was recovered for the shortage on Shift #" + shiftId + AFTER_GENERATED);
    }

    public static CodedText dipCheckRecorded(String tankIdentifier) {
        return CodedText.of(ServerTextCodes.STALE_DIP_CHECK_RECORDED, "tankIdentifier", tankIdentifier,
                "A dip check on tank '" + tankIdentifier + "' was recorded" + AFTER_GENERATED);
    }

    public static CodedText productCostCorrected(String productName, String oldCost, String newCost) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("productName", productName);
        params.put("oldCost", oldCost);
        params.put("newCost", newCost);
        return new CodedText(ServerTextCodes.STALE_PRODUCT_COST_CORRECTED, params,
                "The purchase cost of '" + productName + "' was corrected from ₹" + oldCost + " to ₹" + newCost
                        + AFTER_GENERATED);
    }

    public static CodedText fuelCostCorrected(FuelType fuelType, String invoiceReference, String oldCost, String newCost) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fuelTypeCode", fuelType.name());
        params.put("invoiceReference", invoiceReference);
        params.put("oldCost", oldCost);
        params.put("newCost", newCost);
        return new CodedText(ServerTextCodes.STALE_FUEL_COST_CORRECTED, params,
                "The purchase cost of " + fuelType + " delivery (invoice " + invoiceReference + ") was corrected ₹"
                        + oldCost + " → ₹" + newCost + "/L" + AFTER_GENERATED);
    }

    public static CodedText deliveryChanged(String invoiceReference) {
        return CodedText.of(ServerTextCodes.STALE_DELIVERY_CHANGED, "invoiceReference", invoiceReference,
                "The tanker delivery with invoice " + invoiceReference + " was recorded, edited or deleted" + AFTER_GENERATED);
    }

    public static CodedText expenseChanged(Long expenseId) {
        return CodedText.of(ServerTextCodes.STALE_EXPENSE_CHANGED, "expenseId", expenseId,
                "Expense #" + expenseId + " was recorded, approved or rejected" + AFTER_GENERATED);
    }

    public static CodedText creditPaymentChanged(Long paymentId) {
        return CodedText.of(ServerTextCodes.STALE_CREDIT_PAYMENT_CHANGED, "paymentId", paymentId,
                "Credit payment #" + paymentId + " was recorded, approved or rejected" + AFTER_GENERATED);
    }

    public static CodedText settlementChanged(Long settlementId) {
        return CodedText.of(ServerTextCodes.STALE_SETTLEMENT_CHANGED, "settlementId", settlementId,
                "Settlement #" + settlementId + " was recorded or voided" + AFTER_GENERATED);
    }

    public static CodedText meterAmended(Long nozzleId) {
        return CodedText.of(ServerTextCodes.STALE_METER_AMENDED, "nozzleId", nozzleId,
                "The meter reading of nozzle #" + nozzleId + " was amended" + AFTER_GENERATED);
    }

    public static CodedText discrepancyResolved(Long shiftId) {
        return CodedText.of(ServerTextCodes.STALE_DISCREPANCY_RESOLVED, "shiftId", shiftId,
                "The discrepancy on Shift #" + shiftId + " was resolved" + AFTER_GENERATED);
    }

    public static CodedText payrollPaid(Long payrollId) {
        return CodedText.of(ServerTextCodes.STALE_PAYROLL_PAID, "payrollId", payrollId,
                "Payroll #" + payrollId + " was marked paid on this day" + AFTER_GENERATED);
    }

    /** A credit note, write-off, security deposit or advance refund that changes the day's credit or cash. */
    public static CodedText creditAccountChanged(Long clientId) {
        return CodedText.of(ServerTextCodes.STALE_CREDIT_ACCOUNT_CHANGED, "clientId", clientId,
                "A credit note, write-off, deposit or advance refund was recorded for credit client #" + clientId
                        + AFTER_GENERATED);
    }

    public static CodedText expenseVoided(Long expenseId) {
        return CodedText.of(ServerTextCodes.STALE_EXPENSE_VOIDED, "expenseId", expenseId,
                "Expense #" + expenseId + " was voided" + AFTER_GENERATED);
    }

    public static CodedText chargebackRecorded(Long chargebackId) {
        return CodedText.of(ServerTextCodes.STALE_CHARGEBACK_RECORDED, "chargebackId", chargebackId,
                "Chargeback or refund #" + chargebackId + " was recorded" + AFTER_GENERATED);
    }

    public static CodedText dayReopened(String date) {
        return CodedText.of(ServerTextCodes.STALE_DAY_REOPENED, "date", date,
                date + " was re-opened" + AFTER_GENERATED);
    }

    public static CodedText shiftAmended(Long shiftId) {
        return CodedText.of(ServerTextCodes.STALE_SHIFT_AMENDED, "shiftId", shiftId,
                "Shift #" + shiftId + " was amended" + AFTER_GENERATED);
    }
}
