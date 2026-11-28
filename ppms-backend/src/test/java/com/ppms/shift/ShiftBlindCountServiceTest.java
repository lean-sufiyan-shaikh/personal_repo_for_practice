package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDenominations;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftBlindCountServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock private ShiftOperatorService shiftOperatorService;
    @Mock private ShiftRepository shiftRepository;
    @Mock private ShiftReadModelService shiftReadModelService;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @InjectMocks private ShiftBlindCountService blindCountService;

    private final User operator = User.builder().id(7L).fullName("Ravi").role(UserRole.OPERATOR).build();

    @Test
    void pumpThatCountsBlindNeedsTheCountBeforeTheClose() {
        givenPumpCountsBlind(true);

        assertThatThrownBy(() -> blindCountService.requireCloseMatchesCount(openShift(), closeRequest("900.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("BLIND_COUNT_REQUIRED"));
    }

    @Test
    void pumpThatDoesNotCountBlindClosesWithoutACount() {
        givenPumpCountsBlind(false);

        assertThatCode(() -> blindCountService.requireCloseMatchesCount(openShift(), closeRequest("1000.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void closeMustCarryTheCountedFigures() {
        Shift counted = countedShift("900.00");

        assertThatThrownBy(() -> blindCountService.requireCloseMatchesCount(counted, closeRequest("1000.00")))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("BLIND_COUNT_CHANGED_AT_CLOSE");
                    assertThat(refused.getParams()).containsEntry("cashCounted", "900.00");
                });
        assertThatCode(() -> blindCountService.requireCloseMatchesCount(counted, closeRequest("900")))
                .doesNotThrowAnyException();
    }

    @Test
    void countKeepsTheFiguresAndWhoCountedAndIsAuditedOnce() {
        Shift shift = openShift();
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(shift);
        when(shiftRepository.save(shift)).thenReturn(shift);
        when(businessClock.now()).thenReturn(NOW);

        blindCountService.recordBlindCount(PUMP_ID, SHIFT_ID, count("900"), operator);

        assertThat(shift.getBlindCashCounted()).isEqualTo(new BigDecimal("900.00"));
        assertThat(shift.getBlindUpiCounted()).isEqualTo(new BigDecimal("0.00"));
        assertThat(shift.getBlindCountedByUserId()).isEqualTo(operator.getId());
        assertThat(shift.getBlindCountedAt()).isEqualTo(NOW);
        verify(auditService, times(1)).log(eq(PUMP_ID), eq(AuditAction.BLIND_CASH_COUNTED), eq("Shift"), eq("30"),
                anyString(), eq(operator));
    }

    @Test
    void shiftCanBeCountedOnlyOnce() {
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(countedShift("900.00"));

        assertThatThrownBy(() -> blindCountService.recordBlindCount(PUMP_ID, SHIFT_ID, count("1000.00"), operator))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("BLIND_COUNT_ALREADY_RECORDED"));
        verify(shiftRepository, never()).save(any());
    }

    @Test
    void countWhoseNotesDoNotAddUpIsRefused() {
        when(shiftOperatorService.lockOpenShift(PUMP_ID, SHIFT_ID)).thenReturn(openShift());
        RecordBlindCountRequest wrongNotes = new RecordBlindCountRequest(new BigDecimal("500.00"),
                new CashDenominations(2, 0, 0, 0, 0, 0, BigDecimal.ZERO), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThatThrownBy(() -> blindCountService.recordBlindCount(PUMP_ID, SHIFT_ID, wrongNotes, operator))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("DENOMINATIONS_TOTAL_MISMATCH"));
    }

    private void givenPumpCountsBlind(boolean countsBlind) {
        when(pumpLocationRepository.findById(PUMP_ID))
                .thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).blindCashCount(countsBlind).build()));
    }

    private static Shift openShift() {
        return Shift.builder().id(SHIFT_ID).pumpId(PUMP_ID).operatorId(7L).status(ShiftStatus.OPEN).build();
    }

    private static Shift countedShift(String cash) {
        Shift shift = openShift();
        shift.setBlindCashCounted(new BigDecimal(cash));
        shift.setBlindUpiCounted(new BigDecimal("0.00"));
        shift.setBlindCardCounted(new BigDecimal("0.00"));
        shift.setBlindFleetCardCounted(new BigDecimal("0.00"));
        shift.setBlindCountedAt(NOW);
        shift.setBlindCountedByUserId(7L);
        return shift;
    }

    private static RecordBlindCountRequest count(String cash) {
        return new RecordBlindCountRequest(new BigDecimal(cash), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CloseShiftRequest closeRequest(String cash) {
        CloseShiftRequest request = new CloseShiftRequest();
        request.setCashCollected(new BigDecimal(cash));
        request.setUpiCollected(BigDecimal.ZERO);
        request.setCardCollected(BigDecimal.ZERO);
        request.setFleetCardCollected(BigDecimal.ZERO);
        return request;
    }
}
