package com.ppms.invoice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Printable HTML for a GST credit note, with the seller and buyer of the invoice it credits. */
@Component
@RequiredArgsConstructor
public class GstCreditNoteRenderer {

    private final CreditInvoiceRenderer invoiceRenderer;

    public String render(GstCreditNote note, CreditInvoice invoice) {
        boolean interState = !invoice.getSellerStateCode().equals(invoice.getPlaceOfSupplyStateCode());
        StringBuilder sb = new StringBuilder();
        invoiceRenderer.appendDocumentHead(sb, "Credit Note " + note.getCreditNoteNumber());
        invoiceRenderer.appendSeller(sb, invoice, "CREDIT NOTE");
        sb.append("<div class='meta-row'><div class='meta-block'>")
                .append("<p><strong>Issued to:</strong> ").append(escape(invoice.getBuyerName())).append("</p>");
        if (invoice.getBuyerAddress() != null) {
            sb.append("<p>").append(escape(invoice.getBuyerAddress())).append("</p>");
        }
        sb.append("<p><strong>GSTIN:</strong> ")
                .append(invoice.getBuyerGstin() != null ? escape(invoice.getBuyerGstin()) : "Unregistered")
                .append("</p></div><div class='meta-block'>")
                .append("<p><strong>Credit Note No:</strong> ").append(escape(note.getCreditNoteNumber())).append("</p>")
                .append("<p><strong>Date:</strong> ").append(note.getIssuedOn()).append("</p>")
                .append("<p><strong>Against invoice:</strong> ").append(escape(invoice.getInvoiceNumber()))
                .append(" dated ").append(invoice.getIssuedOn()).append("</p>")
                .append("<p><strong>Place of Supply:</strong> ")
                .append(escape(GstStateCodes.describe(invoice.getPlaceOfSupplyStateCode()))).append("</p>")
                .append("</div></div>")
                .append("<p><strong>Reason:</strong> ").append(escape(note.getReason())).append("</p>");
        appendLine(sb, note, interState);
        sb.append("<div class='no-print'><button onclick='window.print()' ")
                .append("style='padding:8px 20px;font-size:13px;cursor:pointer;'>Print Credit Note</button></div>")
                .append("</body></html>");
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, GstCreditNote note, boolean interState) {
        boolean fuel = note.getLineType() == InvoiceLineType.FUEL;
        sb.append("<div class='table-wrap'><table><thead><tr><th>Description</th><th>HSN</th>")
                .append("<th class='amount'>Qty</th>");
        if (!fuel) {
            sb.append("<th class='amount'>Taxable (₹)</th><th class='amount'>GST %</th>")
                    .append(interState ? "<th class='amount'>IGST (₹)</th>"
                            : "<th class='amount'>CGST (₹)</th><th class='amount'>SGST (₹)</th>");
        }
        sb.append("<th class='amount'>Total (₹)</th></tr></thead><tbody><tr>")
                .append("<td>").append(escape(note.getDescription())).append("</td>")
                .append("<td>").append(escape(note.getHsnCode())).append("</td>")
                .append("<td class='amount'>")
                .append(note.getQuantity() != null ? note.getQuantity().stripTrailingZeros().toPlainString() : "")
                .append("</td>");
        if (!fuel) {
            sb.append(cell(invoiceRenderer.money(note.getTaxableValue()))).append(cell(invoiceRenderer.money(note.getGstRate())));
            if (interState) {
                sb.append(cell(invoiceRenderer.money(note.getIgst())));
            } else {
                sb.append(cell(invoiceRenderer.money(note.getCgst()))).append(cell(invoiceRenderer.money(note.getSgst())));
            }
        }
        sb.append(cell(invoiceRenderer.money(note.getTotal()))).append("</tr></tbody></table></div>");
        if (fuel) {
            sb.append("<p class='note'>Petrol and diesel are outside GST (VAT included in the price).</p>");
        }
    }

    private static String cell(String amount) {
        return "<td class='amount'>" + amount + "</td>";
    }

    private String escape(String value) {
        return invoiceRenderer.escapeHtml(value);
    }
}
