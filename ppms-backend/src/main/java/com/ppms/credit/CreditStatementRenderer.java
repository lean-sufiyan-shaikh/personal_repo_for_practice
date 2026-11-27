package com.ppms.credit;

import com.ppms.collection.QrCodeSvg;
import com.ppms.common.time.BusinessClock;
import com.ppms.fleet.VehicleUsageGroup;
import com.ppms.pump.PumpLocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CreditStatementRenderer {

    private static final int QR_SIZE_PIXELS = 132;
    private static final String UNASSIGNED_VEHICLE_TITLE = "Unassigned / unknown vehicle";
    private static final String REVERSED = "REVERSED";

    private final BusinessClock businessClock;

    /** {@code upiPayLink} adds a QR code to pay the outstanding by UPI; null leaves it out. */
    public String render(PumpLocation pump,
                         CreditClient client,
                         CreditStatement statement,
                         LocalDate fromDate,
                         LocalDate toDate,
                         String upiPayLink) {
        StringBuilder sb = new StringBuilder();
        String periodLabel = (fromDate != null || toDate != null)
                ? "Period: " + (fromDate != null ? fromDate : "inception") + " to " + (toDate != null ? toDate : "today")
                : "All transactions";

        appendDocumentHead(sb, client);
        sb.append("<div class='header'>")
                .append("<h1>").append(escapeHtml(pump.getName())).append("</h1>")
                .append("<h2>").append(escapeHtml(pump.getAddress())).append("</h2>")
                .append("<h2 style='margin-top:8px;color:#333;'>CREDIT ACCOUNT STATEMENT</h2>")
                .append("</div>");

        appendAccountDetails(sb, client, statement.subAccountNames(), periodLabel);

        if (statement.transactions().isEmpty()) {
            sb.append("<p><em>No transactions found for the selected period.</em></p>");
        } else {
            appendTransactionTable(sb, statement.transactions(), statement.coversSubAccounts());
        }

        if (!statement.vehicleUsage().isEmpty()) {
            appendVehicleUsage(sb, statement.vehicleUsage(), statement.coversSubAccounts());
        }

        sb.append("<div class='summary'>")
                .append("<p class='outstanding'>")
                .append(statement.coversSubAccounts() ? "Group Outstanding Balance (incl. sub-accounts): ₹" : "Outstanding Balance: ₹")
                .append(statement.outstanding().setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("</p></div>");

        if (upiPayLink != null) {
            appendUpiPayment(sb, pump, statement.outstanding(), upiPayLink);
        }

        sb.append("<div class='no-print'><button onclick='window.print()' ")
                .append("style='padding:8px 20px;font-size:13px;cursor:pointer;'>Print Statement</button></div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private void appendDocumentHead(StringBuilder sb, CreditClient client) {
        sb.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8"/>
                <title>Credit Statement — %s</title>
                <style>
                  body { font-family: Arial, sans-serif; font-size: 12px; margin: 20px; color: #333; }
                  h1 { font-size: 18px; margin: 0; }
                  h2 { font-size: 14px; margin: 4px 0; color: #555; }
                  .header { border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 16px; }
                  .meta-row { display: flex; justify-content: space-between; margin-bottom: 12px; }
                  .meta-block p { margin: 2px 0; }
                  table { width: 100%%; border-collapse: collapse; margin-top: 10px; }
                  th { background: #f0f0f0; border: 1px solid #ccc; padding: 6px 8px; text-align: left; font-size: 11px; }
                  td { border: 1px solid #ddd; padding: 5px 8px; font-size: 11px; vertical-align: top; }
                  .amount { text-align: right; font-family: monospace; }
                  .balance { text-align: right; font-family: monospace; font-weight: bold; }
                  .type-PAYMENT { color: #1a7a1a; }
                  .type-INTEREST { color: #a05000; }
                  .type-SALE { color: #333; }
                  .summary { margin-top: 20px; text-align: right; }
                  .outstanding { font-size: 14px; font-weight: bold; }
                  .upi { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 12px; }
                  .upi p { margin: 2px 0; text-align: right; }
                  h3.vehicles { font-size: 13px; margin: 20px 0 0; }
                  .vehicle-head td { background: #f7f7f7; font-weight: bold; }
                  .vehicle-total td { font-weight: bold; border-top: 2px solid #ccc; }
                  .no-print { margin-top: 20px; }
                  @media print { .no-print { display: none; } }
                </style>
                </head>
                <body>
                """.formatted(escapeHtml(client.getName())));
    }

    private void appendAccountDetails(StringBuilder sb, CreditClient client, List<String> subAccountNames, String periodLabel) {
        sb.append("<div class='meta-row'>")
                .append("<div class='meta-block'>")
                .append("<p><strong>Account:</strong> ").append(escapeHtml(client.getName())).append("</p>");
        if (!subAccountNames.isEmpty()) {
            sb.append("<p><strong>Sub-accounts:</strong> ")
                    .append(escapeHtml(String.join(", ", subAccountNames))).append("</p>");
        }
        if (client.getPhone() != null) {
            sb.append("<p><strong>Phone:</strong> ").append(escapeHtml(client.getPhone())).append("</p>");
        }
        if (client.getCreditLimit() != null && client.getCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("<p><strong>Credit Limit:</strong> ₹").append(client.getCreditLimit().toPlainString()).append("</p>");
        }
        sb.append("</div>")
                .append("<div class='meta-block' style='text-align:right;'>")
                .append("<p>").append(periodLabel).append("</p>")
                .append("<p>Printed: ").append(businessClock.today()).append("</p>")
                .append("</div></div>");
    }

    private void appendUpiPayment(StringBuilder sb, PumpLocation pump, BigDecimal amount, String upiPayLink) {
        sb.append("<div class='upi'><div>")
                .append("<p><strong>Scan to pay ₹").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append(" with any UPI app</strong></p>")
                .append("<p>").append(escapeHtml(pump.getUpiPayeeName())).append(" · ")
                .append(escapeHtml(pump.getUpiId())).append("</p>")
                .append("</div>")
                .append(QrCodeSvg.render(upiPayLink, QR_SIZE_PIXELS))
                .append("</div>");
    }

    private void appendTransactionTable(StringBuilder sb, List<CreditTransactionResponse> transactions, boolean showAccount) {
        sb.append("<table><thead><tr>")
                .append("<th>Date</th>")
                .append(showAccount ? "<th>Account</th>" : "")
                .append("<th>Type</th><th>Description</th>")
                .append("<th class='amount'>Debit (₹)</th><th class='amount'>Credit (₹)</th>")
                .append("<th class='balance'>Balance (₹)</th>")
                .append("</tr></thead><tbody>");

        for (CreditTransactionResponse tx : transactions) {
            String typeClass = "type-" + tx.getType();
            String debit = "PAYMENT".equals(tx.getType()) ? "" : tx.getAmount().toPlainString();
            String credit = "PAYMENT".equals(tx.getType()) ? tx.getAmount().toPlainString() : "";
            String desc = tx.getReference() != null ? escapeHtml(tx.getReference()) : "";
            if (tx.getDetail() != null) {
                desc += " <span style='color:#888;'>(" + escapeHtml(tx.getDetail()) + ")</span>";
            }
            if (!tx.isCountedInBalance()) {
                desc += " <strong>[" + escapeHtml(tx.getStatus()) + " – not in balance]</strong>";
            } else if (REVERSED.equals(tx.getStatus())) {
                desc += " <strong>[REVERSED – taken back on a later row]</strong>";
            }

            sb.append("<tr class='").append(typeClass).append("'>")
                    .append("<td>").append(tx.getBusinessDate()).append("</td>")
                    .append(showAccount ? "<td>" + escapeHtml(tx.getClientName()) + "</td>" : "")
                    .append("<td>").append(tx.getType()).append("</td>")
                    .append("<td>").append(desc).append("</td>")
                    .append("<td class='amount'>").append(debit).append("</td>")
                    .append("<td class='amount'>").append(credit).append("</td>")
                    .append("<td class='balance'>").append(tx.getRunningBalance().toPlainString()).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");
    }

    /** The period's credit grouped by vehicle, each group with its litres and rupee subtotal. */
    private void appendVehicleUsage(StringBuilder sb, List<VehicleUsageGroup> groups, boolean showAccount) {
        int columns = showAccount ? 6 : 5;
        sb.append("<h3 class='vehicles'>Usage by vehicle</h3><table><thead><tr>")
                .append("<th>Date</th>")
                .append(showAccount ? "<th>Account</th>" : "")
                .append("<th>Item</th><th>Driver</th>")
                .append("<th class='amount'>Litres</th><th class='amount'>Amount (₹)</th>")
                .append("</tr></thead><tbody>");
        for (VehicleUsageGroup group : groups) {
            String title = group.isUnassigned() ? UNASSIGNED_VEHICLE_TITLE : group.registration();
            sb.append("<tr class='vehicle-head'><td colspan='").append(columns).append("'>")
                    .append(escapeHtml(title)).append("</td></tr>");
            for (VehicleUsageGroup.Line line : group.lines()) {
                String item = escapeHtml(line.item());
                if (group.isUnassigned() && line.enteredRegistration() != null) {
                    item += " <span style='color:#888;'>(" + escapeHtml(line.enteredRegistration()) + ")</span>";
                }
                sb.append("<tr>")
                        .append("<td>").append(line.date()).append("</td>")
                        .append(showAccount ? "<td>" + escapeHtml(line.accountName()) + "</td>" : "")
                        .append("<td>").append(item).append("</td>")
                        .append("<td>").append(escapeHtml(line.driverName())).append("</td>")
                        .append("<td class='amount'>").append(line.litres() != null ? line.litres().toPlainString() : "")
                        .append("</td>")
                        .append("<td class='amount'>").append(line.amount().setScale(2, RoundingMode.HALF_UP).toPlainString())
                        .append("</td></tr>");
            }
            sb.append("<tr class='vehicle-total'><td colspan='").append(columns - 2).append("'>Subtotal ")
                    .append(escapeHtml(title)).append("</td>")
                    .append("<td class='amount'>").append(group.litres().toPlainString()).append("</td>")
                    .append("<td class='amount'>").append(group.amount().toPlainString()).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
