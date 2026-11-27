package com.ppms.collection;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The pump's reminder stages, credit hold, UPI details and credit payment approval rules. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionSettingsService {

    static final int MAX_STAGES = 10;
    static final int MAX_DAYS = 365;
    private static final BigDecimal MAX_PAYMENT_THRESHOLD = new BigDecimal("9999999999.99");

    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public CollectionSettingsResponse settingsOf(Long pumpId) {
        return CollectionSettingsResponse.of(requirePump(pumpId));
    }

    @Transactional
    public CollectionSettingsResponse update(Long pumpId, UpdateCollectionSettingsRequest request, User currentUser) {
        PumpLocation pump = requirePump(pumpId);
        Integer[] stages = validStages(request.reminderStageDays());
        Integer holdDays = validHoldDays(request.creditHoldDays());
        BigDecimal paymentThreshold = validPaymentThreshold(request.creditPaymentApprovalThreshold());
        int backdatedDays = validBackdatedDays(request.backdatedPaymentApprovalDays());
        String upiId = trimmedOrNull(request.upiId());
        String payeeName = trimmedOrNull(request.upiPayeeName());
        if ((upiId == null) != (payeeName == null)) {
            throw new BusinessException(ErrorCodes.UPI_DETAILS_INCOMPLETE, Map.of(),
                    "Enter both the UPI ID and the payee name, or leave both empty.");
        }
        if (upiId != null && !UpiPaymentLink.isValidUpiId(upiId)) {
            throw new BusinessException(ErrorCodes.UPI_ID_INVALID, Map.of("upiId", upiId),
                    "'" + upiId + "' is not a UPI ID. It looks like name@bank, e.g. ramfuels@okaxis.");
        }
        if (payeeName != null && !UpiPaymentLink.isValidPayeeName(payeeName)) {
            throw new BusinessException(ErrorCodes.UPI_PAYEE_NAME_INVALID, Map.of(),
                    "The payee name can use letters, digits, spaces and . & ' - only, up to 100 characters.");
        }

        pump.setReminderStageDays(stages);
        pump.setCreditHoldDays(holdDays);
        pump.setUpiId(upiId);
        pump.setUpiPayeeName(payeeName);
        pump.setCreditPaymentApprovalThreshold(paymentThreshold);
        pump.setBackdatedPaymentApprovalDays(backdatedDays);
        if (request.groupInvoiceByDefault() != null) {
            pump.setGroupInvoiceByDefault(request.groupInvoiceByDefault());
        }
        pumpLocationRepository.save(pump);

        String description = "Reminder stages=" + Arrays.toString(stages) + ", creditHoldDays="
                + (holdDays == null ? "off" : holdDays) + ", upiId=" + (upiId == null ? "none" : upiId)
                + ", creditPaymentApprovalThreshold=" + (paymentThreshold == null ? "none" : paymentThreshold)
                + ", backdatedPaymentApprovalDays=" + backdatedDays
                + ", groupInvoiceByDefault=" + pump.isGroupInvoiceByDefault();
        auditService.log(pumpId, AuditAction.COLLECTION_SETTINGS_UPDATED, "PumpLocation", pumpId.toString(),
                description, currentUser);
        log.info("Collection settings of pump={} updated by user={}: {}", pumpId, currentUser.getId(), description);
        return CollectionSettingsResponse.of(pump);
    }

    /** Distinct days from 1 to 365, at most ten, stored ascending. */
    static Integer[] validStages(List<Integer> stageDays) {
        List<Integer> sorted = stageDays.stream().filter(Objects::nonNull).sorted().toList();
        boolean valid = sorted.size() == stageDays.size()
                && sorted.size() <= MAX_STAGES
                && sorted.stream().distinct().count() == sorted.size()
                && sorted.stream().allMatch(days -> days >= 1 && days <= MAX_DAYS);
        if (!valid) {
            throw new BusinessException(ErrorCodes.REMINDER_STAGES_INVALID,
                    Map.of("maxStages", MAX_STAGES, "maxDays", MAX_DAYS),
                    "Reminder stages must be different days from 1 to " + MAX_DAYS + ", at most " + MAX_STAGES + ".");
        }
        return sorted.toArray(Integer[]::new);
    }

    private static Integer validHoldDays(Integer holdDays) {
        if (holdDays != null && (holdDays < 1 || holdDays > MAX_DAYS)) {
            throw new BusinessException(ErrorCodes.CREDIT_HOLD_DAYS_OUT_OF_RANGE, Map.of("maxDays", MAX_DAYS),
                    "Credit hold days must be from 1 to " + MAX_DAYS + ", or empty to turn the hold off.");
        }
        return holdDays;
    }

    private static BigDecimal validPaymentThreshold(BigDecimal threshold) {
        if (threshold == null) {
            return null;
        }
        if (threshold.signum() <= 0 || threshold.scale() > 2 || threshold.compareTo(MAX_PAYMENT_THRESHOLD) > 0) {
            throw new BusinessException(ErrorCodes.CREDIT_PAYMENT_THRESHOLD_INVALID,
                    Map.of("maxAmount", MAX_PAYMENT_THRESHOLD.toPlainString()),
                    "The credit payment approval amount must be more than ₹0 and at most ₹"
                            + MAX_PAYMENT_THRESHOLD.toPlainString() + ", in rupees and paise, or empty for no limit.");
        }
        return threshold.setScale(2, RoundingMode.HALF_UP);
    }

    private static int validBackdatedDays(int days) {
        if (days < 0 || days > MAX_DAYS) {
            throw new BusinessException(ErrorCodes.BACKDATED_PAYMENT_DAYS_OUT_OF_RANGE, Map.of("maxDays", MAX_DAYS),
                    "Back-dated payment days must be from 0 to " + MAX_DAYS + ".");
        }
        return days;
    }

    private static String trimmedOrNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private PumpLocation requirePump(Long pumpId) {
        return pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
    }
}
