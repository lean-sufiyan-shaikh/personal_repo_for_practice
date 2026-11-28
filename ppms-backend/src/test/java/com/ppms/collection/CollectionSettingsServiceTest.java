package com.ppms.collection;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionSettingsServiceTest {

    private static final long PUMP_ID = 7L;

    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final CollectionSettingsService settingsService = new CollectionSettingsService(pumpLocationRepository,
            mock(AuditService.class));

    @BeforeEach
    void givenPump() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
    }

    @Test
    void upiIdThatIsNotNameAtBankIsRefused() {
        assertThatThrownBy(() -> save(List.of(7, 15), 45, "not-a-upi-id", "Ram Fuels"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("UPI_ID_INVALID");
    }

    @Test
    void upiIdWithoutAPayeeNameIsRefused() {
        assertThatThrownBy(() -> save(List.of(7, 15), 45, "ramfuels@okaxis", " "))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("UPI_DETAILS_INCOMPLETE");
    }

    @Test
    void payeeNameThatCouldAlterTheUpiLinkIsRefused() {
        assertThatThrownBy(() -> save(List.of(7, 15), 45, "ramfuels@okaxis", "Ram?pa=x"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("UPI_PAYEE_NAME_INVALID");
    }

    @Test
    void repeatedOrZeroDayReminderStagesAreRefused() {
        assertThatThrownBy(() -> save(List.of(7, 7), 45, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REMINDER_STAGES_INVALID");
        assertThatThrownBy(() -> save(List.of(0), 45, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("REMINDER_STAGES_INVALID");
    }

    @Test
    void holdDaysBelowOneAreRefused() {
        assertThatThrownBy(() -> save(List.of(7), 0, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_HOLD_DAYS_OUT_OF_RANGE");
    }

    @Test
    void creditPaymentThresholdOfZeroAndBackdatedDaysAboveAYearAreRefused() {
        assertThatThrownBy(() -> save(List.of(7), 45, BigDecimal.ZERO, 7))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_PAYMENT_THRESHOLD_INVALID");
        assertThatThrownBy(() -> save(List.of(7), 45, new BigDecimal("1000.00"), 366))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("BACKDATED_PAYMENT_DAYS_OUT_OF_RANGE");
    }

    @Test
    void creditPaymentThresholdAndBackdatedDaysAreSaved() {
        CollectionSettingsResponse saved = save(List.of(7), 45, new BigDecimal("1000.00"), 30);

        assertThat(saved.creditPaymentApprovalThreshold()).isEqualByComparingTo("1000.00");
        assertThat(saved.backdatedPaymentApprovalDays()).isEqualTo(30);
    }

    @Test
    void validSettingsAreSavedWithStagesInOrderAndTheUpiIdTrimmed() {
        CollectionSettingsResponse saved = save(List.of(30, 10), 60, " ramfuels@okaxis ", "Ram Fuels");

        assertThat(saved.reminderStageDays()).containsExactly(10, 30);
        assertThat(saved.creditHoldDays()).isEqualTo(60);
        assertThat(saved.upiId()).isEqualTo("ramfuels@okaxis");
    }

    private CollectionSettingsResponse save(List<Integer> stages, Integer holdDays, String upiId, String payeeName) {
        return update(new UpdateCollectionSettingsRequest(stages, holdDays, upiId, payeeName, null, 3, null));
    }

    private CollectionSettingsResponse save(List<Integer> stages, Integer holdDays, BigDecimal paymentThreshold,
                                            int backdatedDays) {
        return update(new UpdateCollectionSettingsRequest(stages, holdDays, null, null, paymentThreshold,
                backdatedDays, null));
    }

    private CollectionSettingsResponse update(UpdateCollectionSettingsRequest request) {
        User owner = new User();
        owner.setId(3L);
        return settingsService.update(PUMP_ID, request, owner);
    }
}
