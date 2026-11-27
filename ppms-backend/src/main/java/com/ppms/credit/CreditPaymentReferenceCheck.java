package com.ppms.credit;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reference and cheque details of a new credit payment: UPI, bank transfer and cheque payments need a reference
 * (the cheque number for a cheque), and a reference already used on the pump in the last 90 days must be confirmed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditPaymentReferenceCheck {

    static final int DUPLICATE_LOOKBACK_DAYS = 90;
    private static final Set<PaymentMode> MODES_NEEDING_REFERENCE =
            EnumSet.of(PaymentMode.UPI, PaymentMode.BANK_TRANSFER, PaymentMode.CHEQUE);

    private final CreditPaymentRepository paymentRepository;
    private final BusinessClock businessClock;

    /** The trimmed reference, or null when the mode needs none and none was given. */
    public String checkedReference(Long pumpId, RecordPaymentRequest request) {
        PaymentMode mode = request.getPaymentMode();
        String reference = trimmedOrNull(request.getReferenceNo());
        if (reference == null && MODES_NEEDING_REFERENCE.contains(mode)) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_REFERENCE_REQUIRED,
                    Map.of("modeCode", mode.name()),
                    "A " + mode + " payment needs its reference (UTR, transaction ID or cheque number).");
        }
        if (mode != PaymentMode.CHEQUE && (trimmedOrNull(request.getChequeBank()) != null || request.getChequeDate() != null)) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_CHEQUE_DETAILS_NOT_CHEQUE,
                    Map.of("modeCode", mode.name()),
                    "Cheque bank and cheque date can only be given for a cheque payment.");
        }
        if (reference != null && !request.isConfirmDuplicate()) {
            refuseUnconfirmedDuplicate(pumpId, reference);
        }
        return reference;
    }

    private void refuseUnconfirmedDuplicate(Long pumpId, String reference) {
        List<CreditPayment> sameReference = paymentRepository.findSameReferenceSince(pumpId, reference,
                businessClock.today().minusDays(DUPLICATE_LOOKBACK_DAYS));
        if (sameReference.isEmpty()) {
            return;
        }
        CreditPayment earlier = sameReference.get(0);
        log.info("Possible duplicate credit payment refused until confirmed: pump={} reference={} earlierPaymentId={}",
                pumpId, reference, earlier.getId());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("referenceNo", reference);
        params.put("amount", earlier.getAmount().toPlainString());
        params.put("paidAt", earlier.getPaidAt().toString());
        throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_POSSIBLE_DUPLICATE, params,
                "Possible duplicate: a payment with reference " + reference + " (₹" + earlier.getAmount().toPlainString()
                        + ", dated " + earlier.getPaidAt() + ") was already recorded in the last "
                        + DUPLICATE_LOOKBACK_DAYS + " days. Check it, then record again to confirm.");
    }

    static String trimmedOrNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
