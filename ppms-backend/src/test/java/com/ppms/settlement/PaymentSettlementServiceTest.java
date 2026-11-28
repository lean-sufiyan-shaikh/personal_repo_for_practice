package com.ppms.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {

    private static final long PUMP_ID = 4L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private PaymentSettlementRepository settlementRepository;
    @Mock private PaymentSettlementConfigRepository configRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @InjectMocks private PaymentSettlementService settlementService;

    private final User manager = User.builder().id(2L).fullName("Manager").role(UserRole.MANAGER).build();

    @Test
    void settlementDatedInTheFutureIsRefused() {
        when(businessClock.today()).thenReturn(TODAY);

        assertRefused(upi("10.00", TODAY.plusDays(2), null), "SETTLEMENT_DATE_IN_FUTURE");
    }

    @Test
    void settlementLargerThanWhatIsPendingIsRefusedWithThePendingAmount() {
        givenPumpAndClock();
        when(shiftRepository.sumUpiCollectedByPumpId(PUMP_ID)).thenReturn(new BigDecimal("600.00"));
        when(settlementRepository.sumAmountByPumpIdAndPaymentType(PUMP_ID, SettlementPaymentType.UPI))
                .thenReturn(new BigDecimal("450.00"));

        assertThatThrownBy(() -> settlementService.record(PUMP_ID, upi("150.01", TODAY, "UTR-OVER"), manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("SETTLEMENT_EXCEEDS_PENDING");
                    assertThat(refused.getParams()).containsEntry("paymentTypeCode", "UPI")
                            .containsEntry("amount", "150.01").containsEntry("pending", "150.00");
                    assertThat(refused.getMessage()).contains("₹150.00 still pending");
                });
        verify(settlementRepository, never()).save(any());
    }

    @Test
    void identicalSettlementWithinTwoMinutesIsRefused() {
        givenPumpAndClock();
        when(settlementRepository.existsRecentDuplicate(eq(PUMP_ID), eq(SettlementPaymentType.UPI), eq(TODAY),
                eq(new BigDecimal("100.00")), any())).thenReturn(true);

        assertThatThrownBy(() -> settlementService.record(PUMP_ID, upi("100.00", TODAY, null), manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("SETTLEMENT_RECENT_DUPLICATE");
                    assertThat(refused.getMessage()).contains("last 2 minutes");
                });
    }

    @Test
    void bankReferenceAlreadyRecordedAtThePumpIsRefused() {
        givenPumpAndClock();
        when(settlementRepository.existsActiveWithBankReference(PUMP_ID, "UTR-123456")).thenReturn(true);

        assertThatThrownBy(() -> settlementService.record(PUMP_ID, upi("200.00", TODAY, " UTR-123456 "), manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("SETTLEMENT_BANK_REFERENCE_DUPLICATE");
                    assertThat(refused.getMessage()).contains("UTR-123456");
                });
    }

    @Test
    void gstWithoutTheMdrItIsChargedOnIsRefused() {
        givenPumpAndClock();
        RecordSettlementRequest request = upi("100.00", TODAY, null);
        request.setGstOnMdrAmount(new BigDecimal("1.00"));

        assertRefused(request, "SETTLEMENT_GST_WITHOUT_MDR");
    }

    @Test
    void voidNeedsAReason() {
        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(new VoidSettlementRequest(" ")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("reason");
    }

    @Test
    void unknownPaymentTypeCannotBeRead() {
        assertThatThrownBy(() -> new ObjectMapper().findAndRegisterModules().readValue(
                "{\"paymentType\":\"CHEQUE\",\"settlementDate\":\"2026-11-28\",\"amountReceived\":10}", RecordSettlementRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    private void givenPumpAndClock() {
        when(businessClock.today()).thenReturn(TODAY);
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
        when(configRepository.findByPumpIdAndPaymentType(eq(PUMP_ID), any())).thenReturn(Optional.empty());
        lenient().when(businessClock.now())
                .thenReturn(OffsetDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    private void assertRefused(RecordSettlementRequest request, String code) {
        assertThatThrownBy(() -> settlementService.record(PUMP_ID, request, manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(settlementRepository, never()).save(any());
    }

    private static RecordSettlementRequest upi(String amount, LocalDate date, String bankReference) {
        return RecordSettlementRequest.builder()
                .paymentType(SettlementPaymentType.UPI)
                .settlementDate(date)
                .amountReceived(new BigDecimal(amount))
                .bankReference(bankReference)
                .build();
    }
}
