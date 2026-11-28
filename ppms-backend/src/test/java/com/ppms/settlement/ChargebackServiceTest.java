package com.ppms.settlement;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargebackServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SETTLEMENT_ID = 90L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private SettlementChargebackRepository chargebackRepository;
    @Mock private PaymentSettlementRepository settlementRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private BusinessClock businessClock;
    @InjectMocks private ChargebackService chargebackService;

    private final User owner = User.builder().id(1L).fullName("Owner").role(UserRole.OWNER).build();

    @Test
    void chargebackDatedInTheFutureIsRefused() {
        when(businessClock.today()).thenReturn(TODAY);

        assertRefused(againstSettlement("10.00", null, TODAY.plusDays(1)), "CHARGEBACK_DATE_IN_FUTURE");
    }

    @Test
    void chargebackLargerThanWhatIsLeftOfTheSettlementIsRefused() {
        givenPumpAndSettlement();
        when(chargebackRepository.sumAmountBySettlementId(SETTLEMENT_ID)).thenReturn(new BigDecimal("150.00"));

        assertThatThrownBy(() -> chargebackService.record(PUMP_ID, againstSettlement("450.01", null, TODAY), owner))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("CHARGEBACK_EXCEEDS_SETTLEMENT");
                    assertThat(refused.getParams()).containsEntry("remaining", "450.00");
                });
        verify(chargebackRepository, never()).save(any());
    }

    @Test
    void chargebackWithoutASettlementNeedsThePaymentTypeAndCollectionDay() {
        when(businessClock.today()).thenReturn(TODAY);
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));

        assertRefused(new RecordChargebackRequest(ChargebackKind.CHARGEBACK, null, TODAY, new BigDecimal("10.00"), null,
                "Disputed", null, null), "CHARGEBACK_LINK_REQUIRED");
    }

    @Test
    void referenceAlreadyRecordedAtThePumpIsRefused() {
        givenPumpAndSettlement();
        when(chargebackRepository.sumAmountBySettlementId(SETTLEMENT_ID)).thenReturn(BigDecimal.ZERO);
        when(chargebackRepository.existsWithReference(PUMP_ID, "cb-1")).thenReturn(true);

        assertRefused(againstSettlement("10.00", " cb-1 ", TODAY), "CHARGEBACK_REFERENCE_DUPLICATE");
    }

    private void givenPumpAndSettlement() {
        when(businessClock.today()).thenReturn(TODAY);
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
        when(settlementRepository.findByIdAndPumpId(SETTLEMENT_ID, PUMP_ID)).thenReturn(Optional.of(PaymentSettlement.builder()
                .id(SETTLEMENT_ID).pumpId(PUMP_ID).paymentType(SettlementPaymentType.CARD).amountReceived(new BigDecimal("600.00"))
                .netAmount(new BigDecimal("600.00")).build()));
    }

    private void assertRefused(RecordChargebackRequest request, String code) {
        assertThatThrownBy(() -> chargebackService.record(PUMP_ID, request, owner))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(chargebackRepository, never()).save(any());
    }

    private static RecordChargebackRequest againstSettlement(String amount, String reference, LocalDate date) {
        return new RecordChargebackRequest(ChargebackKind.CHARGEBACK, null, date, new BigDecimal(amount), reference,
                "Card holder disputed the payment", SETTLEMENT_ID, null);
    }
}
