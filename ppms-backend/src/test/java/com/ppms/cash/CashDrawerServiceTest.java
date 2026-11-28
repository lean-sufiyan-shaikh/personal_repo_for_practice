package com.ppms.cash;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashDrawerServiceTest {

    private static final long PUMP_ID = 4L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 27);

    @Mock private CashEventRepository cashEventRepository;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @InjectMocks private CashDrawerService drawerService;

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();
    private final User manager = User.builder().id(2L).role(UserRole.MANAGER).build();

    @BeforeEach
    void givenPumpAndSaves() {
        lenient().when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
        lenient().when(cashEventRepository.save(any(CashEvent.class))).thenAnswer(invocation -> {
            CashEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId(99L);
            }
            return event;
        });
        lenient().when(businessClock.now()).thenReturn(OffsetDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(value = CashEventType.class, names = {"CASH_DROP", "BANK_DEPOSIT"})
    void dropsAndDepositsCannotBeTypedInByHand(CashEventType type) {
        assertRecordRefused(request(type, "10.00"), manager, "CASH_EVENT_TYPE_NOT_MANUAL");
    }

    @Test
    void onlyAnOwnerOrAdminRecordsTheOpeningBalance() {
        assertRecordRefused(request(CashEventType.OPENING_BALANCE, "5000.00"), manager, "CASH_OPENING_BALANCE_OWNER_ONLY");
    }

    @Test
    void secondOpeningBalanceIsRefusedUntilTheFirstIsReversed() {
        when(cashEventRepository.existsByPumpIdAndEventTypeAndReversedAtIsNull(PUMP_ID, CashEventType.OPENING_BALANCE))
                .thenReturn(true);

        assertRecordRefused(request(CashEventType.OPENING_BALANCE, "6000.00"), owner, "CASH_OPENING_BALANCE_EXISTS");
    }

    @Test
    void cashOutLargerThanTheBalanceIsRefused() {
        when(cashEventRepository.computeBalance(PUMP_ID)).thenReturn(new BigDecimal("100.00"));

        assertRecordRefused(request(CashEventType.CASH_OUT, "100.01"), manager, "CASH_OUT_EXCEEDS_BALANCE");
    }

    @Test
    void cashMovementTypedByHandIsMarkedAsSuch() {
        CashEvent event = drawerService.recordCashEvent(PUMP_ID, request(CashEventType.CASH_IN, "200"), manager);

        assertThat(event.isRecordedByHand()).isTrue();
        assertThat(event.getAmount()).isEqualTo(new BigDecimal("200.00"));
        verify(closedDayGuard).requireOpen(PUMP_ID, DAY);
    }

    @Test
    void reversingACashOutPutsTheMoneyBackOnTheSameDay() {
        CashEvent cashOut = givenEvent(CashEventType.CASH_OUT, true);

        CashEvent reversal = drawerService.reverseCashEvent(PUMP_ID, cashOut.getId(), " Wrong amount ", owner);

        assertThat(reversal.getEventType()).isEqualTo(CashEventType.CASH_IN);
        assertThat(reversal.getAmount()).isEqualByComparingTo("300.00");
        assertThat(reversal.getEventDate()).isEqualTo(DAY);
        assertThat(reversal.getReversesEventId()).isEqualTo(cashOut.getId());
        assertThat(reversal.getDescription()).endsWith(": Wrong amount");
        assertThat(cashOut.getReversedAt()).isNotNull();
        verify(closedDayGuard).requireOpen(PUMP_ID, DAY);
    }

    @Test
    void reversingTheOpeningBalanceTakesItOut() {
        CashEvent opening = givenEvent(CashEventType.OPENING_BALANCE, true);

        assertThat(drawerService.reverseCashEvent(PUMP_ID, opening.getId(), "Typed 5000 instead of 500", owner).getEventType())
                .isEqualTo(CashEventType.CASH_OUT);
    }

    @Test
    void movementCannotBeReversedTwice() {
        CashEvent cashOut = givenEvent(CashEventType.CASH_OUT, true);
        cashOut.setReversedAt(OffsetDateTime.of(2026, 11, 27, 18, 0, 0, 0, ZoneOffset.UTC));

        assertReverseRefused(cashOut, "CASH_EVENT_ALREADY_REVERSED");
    }

    @Test
    void reversalEntriesAndAutomaticMovementsCannotBeReversed() {
        CashEvent reversal = givenEvent(CashEventType.CASH_OUT, false);
        reversal.setReversesEventId(5L);
        CashEvent automatic = givenEvent(CashEventType.CASH_IN, false);
        automatic.setId(51L);
        when(cashEventRepository.findById(51L)).thenReturn(Optional.of(automatic));

        assertReverseRefused(reversal, "CASH_EVENT_NOT_REVERSIBLE");
        assertReverseRefused(automatic, "CASH_EVENT_NOT_REVERSIBLE");
    }

    private CashEvent givenEvent(CashEventType type, boolean byHand) {
        CashEvent event = CashEvent.builder().id(50L).pumpId(PUMP_ID).eventType(type).amount(new BigDecimal("300.00"))
                .eventDate(DAY).description("Movement").recordedByHand(byHand).build();
        lenient().when(cashEventRepository.findById(50L)).thenReturn(Optional.of(event));
        return event;
    }

    private void assertRecordRefused(RecordCashEventRequest request, User user, String code) {
        assertThatThrownBy(() -> drawerService.recordCashEvent(PUMP_ID, request, user))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(cashEventRepository, never()).save(any());
    }

    private void assertReverseRefused(CashEvent event, String code) {
        assertThatThrownBy(() -> drawerService.reverseCashEvent(PUMP_ID, event.getId(), "Undo", owner))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
    }

    private static RecordCashEventRequest request(CashEventType type, String amount) {
        RecordCashEventRequest request = new RecordCashEventRequest();
        request.setEventType(type);
        request.setAmount(new BigDecimal(amount));
        request.setDescription("By hand");
        request.setEventDate(DAY);
        return request;
    }
}
