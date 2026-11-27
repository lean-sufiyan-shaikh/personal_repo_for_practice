package com.ppms.invoice;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.credit.CreditAdjustmentRepository;
import com.ppms.credit.CreditDebitType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stops a sale on an issued invoice, or one with a credit note against it, from being voided or moved, so the invoice
 * and the credit note keep matching the books.
 */
@Service
@RequiredArgsConstructor
public class InvoicedSaleGuard {

    private final CreditInvoiceLineRepository lineRepository;
    private final CreditAdjustmentRepository adjustmentRepository;

    public void requireFuelEntriesNotInvoiced(Collection<Long> creditEntryIds) {
        if (creditEntryIds.isEmpty()) {
            return;
        }
        List<String> invoiceNumbers = lineRepository.findActiveInvoiceNumbersOfSources(InvoiceLineType.FUEL, creditEntryIds);
        if (!invoiceNumbers.isEmpty()) {
            throw new BusinessException(ErrorCodes.SALE_ALREADY_INVOICED,
                    Map.of("invoiceNumbers", String.join(", ", invoiceNumbers)),
                    "This sale is on invoice " + String.join(", ", invoiceNumbers)
                    + ". Cancel the invoice first.");
        }
        if (adjustmentRepository.existsCreditNoteOnDebits(CreditDebitType.SALE, creditEntryIds)) {
            throw new BusinessException(ErrorCodes.CREDIT_SALE_HAS_CREDIT_NOTE, Map.of(),
                    "This sale has a credit note against it, so it can no longer be voided or moved.");
        }
    }
}
