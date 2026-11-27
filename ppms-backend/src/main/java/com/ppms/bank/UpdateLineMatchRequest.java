package com.ppms.bank;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** MATCHED needs exactly one linked record; IGNORED needs the reason in matchNotes. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdateLineMatchRequest {
    /** MATCHED, IGNORED or UNMATCHED. */
    private String status;
    private Long matchedShiftId;
    private Long matchedSaleId;
    private Long matchedPaymentId;
    private Long matchedSettlementId;
    private Long matchedDepositId;
    private Long matchedSupplierPaymentId;
    @Size(max = 500, message = "matchNotes must be at most 500 characters.")
    private String matchNotes;

    List<BankMatchRef> links() {
        List<BankMatchRef> links = new ArrayList<>();
        addIfPresent(links, BankMatchRecordType.SHIFT, matchedShiftId);
        addIfPresent(links, BankMatchRecordType.ANCILLARY_SALE, matchedSaleId);
        addIfPresent(links, BankMatchRecordType.CREDIT_PAYMENT, matchedPaymentId);
        addIfPresent(links, BankMatchRecordType.SETTLEMENT, matchedSettlementId);
        addIfPresent(links, BankMatchRecordType.DEPOSIT, matchedDepositId);
        addIfPresent(links, BankMatchRecordType.SUPPLIER_PAYMENT, matchedSupplierPaymentId);
        return links;
    }

    private static void addIfPresent(List<BankMatchRef> links, BankMatchRecordType type, Long id) {
        if (id != null) {
            links.add(new BankMatchRef(type, id));
        }
    }
}
