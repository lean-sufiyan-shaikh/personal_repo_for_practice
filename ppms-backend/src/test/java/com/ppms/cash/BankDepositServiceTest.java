package com.ppms.cash;

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
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankDepositServiceTest {

    private static final long PUMP_ID = 4L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private BankDepositRepository depositRepository;
    @Mock private BankDepositSlipRepository slipRepository;
    @Mock private CashEventRepository cashEventRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @InjectMocks private BankDepositService depositService;

    private final User manager = User.builder().id(2L).fullName("Manager").role(UserRole.MANAGER).build();

    @Test
    void depositDatedInTheFutureIsRefused() {
        givenPumpAndToday();

        assertDepositRefused(deposit("100.00", "S-1", TODAY.plusDays(1)), "BANK_DEPOSIT_DATE_IN_FUTURE");
    }

    @Test
    void slipReferenceAlreadyUsedAtThePumpIsRefused() {
        givenPumpAndToday();
        when(depositRepository.existsByPumpIdAndSlipReferenceIgnoreCase(PUMP_ID, "s-1")).thenReturn(true);

        assertDepositRefused(deposit("100.00", " s-1 ", TODAY), "BANK_DEPOSIT_SLIP_REFERENCE_USED");
    }

    @Test
    void depositLargerThanTheSafeHeldThatDayIsRefused() {
        givenPumpAndToday();
        when(cashEventRepository.computeBalanceAtEndOf(PUMP_ID, TODAY)).thenReturn(new BigDecimal("1000"));

        assertThatThrownBy(() -> depositService.recordDeposit(PUMP_ID, deposit("1000.01", "S-1", TODAY), manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("BANK_DEPOSIT_EXCEEDS_SAFE_BALANCE");
                    assertThat(refused.getParams()).containsEntry("balance", "1000.00");
                });
    }

    @Test
    void backDatedDepositThatLeavesALaterDayBelowZeroIsRefused() {
        givenPumpAndToday();
        LocalDate threeDaysAgo = TODAY.minusDays(3);
        when(cashEventRepository.computeBalanceAtEndOf(PUMP_ID, threeDaysAgo)).thenReturn(new BigDecimal("1000.00"));
        when(cashEventRepository.findLowestEndOfDayBalanceFrom(PUMP_ID, threeDaysAgo)).thenReturn(new BigDecimal("200"));

        assertThatThrownBy(() -> depositService.recordDeposit(PUMP_ID, deposit("500.00", "B-1", threeDaysAgo), manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("BANK_DEPOSIT_LEAVES_SAFE_NEGATIVE");
                    assertThat(refused.getParams()).containsEntry("lowestBalance", "200.00");
                });
        verify(cashEventRepository, never()).save(any());
    }

    @Test
    void emptySlipAndAFileThatIsNotAPhotoOrPdfAreRefused() {
        when(depositRepository.findByIdAndPumpId(80L, PUMP_ID)).thenReturn(Optional.of(BankDeposit.builder().id(80L).build()));

        assertSlipRefused(new MockMultipartFile("file", "slip.png", "image/png", new byte[0]), "DEPOSIT_SLIP_EMPTY");
        assertSlipRefused(new MockMultipartFile("file", "slip.png", "image/png", "<svg/>".getBytes(StandardCharsets.UTF_8)),
                "DEPOSIT_SLIP_TYPE_NOT_ALLOWED");
        verify(slipRepository, never()).save(any());
    }

    private void givenPumpAndToday() {
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
        when(businessClock.today()).thenReturn(TODAY);
    }

    private void assertDepositRefused(RecordBankDepositRequest request, String code) {
        assertThatThrownBy(() -> depositService.recordDeposit(PUMP_ID, request, manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(cashEventRepository, never()).save(any());
    }

    private void assertSlipRefused(MockMultipartFile file, String code) {
        assertThatThrownBy(() -> depositService.attachSlip(PUMP_ID, 80L, file, manager))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
    }

    private static RecordBankDepositRequest deposit(String amount, String slipReference, LocalDate date) {
        return new RecordBankDepositRequest(new BigDecimal(amount), "SBI ••1234", date, slipReference, null);
    }
}
