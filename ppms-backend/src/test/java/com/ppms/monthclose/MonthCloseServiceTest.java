package com.ppms.monthclose;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.DayClose;
import com.ppms.dayclose.DayCloseRepository;
import com.ppms.dayclose.DayCloseStatus;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonthCloseServiceTest {

    private static final Long PUMP_ID = 4L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final YearMonth MONTH = YearMonth.of(2026, 10);
    private static final LocalDate UNCLOSED_DAY = MONTH.atDay(10);
    private static final LocalDate UNAPPROVED_DAY = MONTH.atDay(20);

    private final MonthCloseRepository monthCloseRepository = mock(MonthCloseRepository.class);
    private final MonthCloseVersionRepository versionRepository = mock(MonthCloseVersionRepository.class);
    private final DayCloseRepository dayCloseRepository = mock(DayCloseRepository.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final MonthSummaryBuilder summaryBuilder = mock(MonthSummaryBuilder.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BusinessClock businessClock = mock(BusinessClock.class);

    private final MonthCloseService service = new MonthCloseService(monthCloseRepository, versionRepository,
            dayCloseRepository, pumpLocationRepository, mock(UserRepository.class), summaryBuilder, auditService, businessClock);

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).fullName("Owner").build();
    private final PumpLocation pump = new PumpLocation();

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 11, 27));
        when(businessClock.now()).thenReturn(LocalDate.of(2026, 11, 27).atTime(10, 0).atZone(ZONE).toOffsetDateTime());
        when(businessClock.zone()).thenReturn(ZONE);
        pump.setCreatedAt(MONTH.minusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime());
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(pump));
        when(monthCloseRepository.findByPumpIdAndMonthStart(PUMP_ID, MONTH.atDay(1))).thenReturn(Optional.empty());
        when(monthCloseRepository.save(any(MonthClose.class))).thenAnswer(invocation -> {
            MonthClose close = invocation.getArgument(0);
            if (close.getId() == null) {
                close.setId(30L);
            }
            return close;
        });
        when(summaryBuilder.build(any(), any(), anyList(), anyInt(), anyList(), any())).thenReturn(mock(MonthSummary.class));
        when(dayCloseRepository.findByPumpIdAndBusinessDateBetween(PUMP_ID, MONTH.atDay(1), MONTH.atEndOfMonth()))
                .thenReturn(daysClosedExceptOneUnclosedAndOneUnapproved());
    }

    @Test
    void daysNeitherApprovedNorExcusedAreCountedAndRefused() {
        assertThatThrownBy(() -> service.close(PUMP_ID, MONTH, new MonthCloseRequests.Close(null), owner))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.MONTH_DAYS_NOT_READY);
                    assertThat(error.getParams()).containsEntry("count", 2)
                            .containsEntry("dates", List.of(UNCLOSED_DAY.toString(), UNAPPROVED_DAY.toString()));
                });
        verify(monthCloseRepository, never()).save(any());
    }

    @Test
    void exceptionOnADayOutsideTheMonthIsRefused() {
        MonthCloseRequests.Close request = new MonthCloseRequests.Close(
                List.of(new MonthCloseRequests.ExceptionDay(MONTH.plusMonths(1).atDay(3), "Not this month")));

        assertThatThrownBy(() -> service.close(PUMP_ID, MONTH, request, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.MONTH_EXCEPTION_NOT_IN_MONTH));
    }

    @Test
    void daysBeforeThePumpWasSetUpAreNotBusinessDays() {
        pump.setCreatedAt(UNAPPROVED_DAY.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());

        service.close(PUMP_ID, MONTH, new MonthCloseRequests.Close(null), owner);

        ArgumentCaptor<List<LocalDate>> businessDays = ArgumentCaptor.forClass(List.class);
        verify(summaryBuilder).build(eq(PUMP_ID), eq(MONTH), businessDays.capture(), anyInt(), anyList(), any());
        assertThat(businessDays.getValue()).first().isEqualTo(UNAPPROVED_DAY.plusDays(1));
        assertThat(businessDays.getValue()).last().isEqualTo(MONTH.atEndOfMonth());
    }

    @Test
    void excusedDaysCloseTheMonthAsTheFirstVersionWithEachExceptionAndItsDayStatus() {
        service.close(PUMP_ID, MONTH, closeWithBothExceptions(), owner);

        ArgumentCaptor<List<MonthSummary.ExceptionDay>> exceptions = ArgumentCaptor.forClass(List.class);
        verify(summaryBuilder).build(eq(PUMP_ID), eq(MONTH), anyList(), eq(MONTH.lengthOfMonth() - 2),
                exceptions.capture(), any());
        assertThat(exceptions.getValue()).extracting(MonthSummary.ExceptionDay::statusCode)
                .containsExactly("NOT_CLOSED", "CLOSED");
        assertThat(exceptions.getValue().get(0).reason()).isEqualTo("Pump shut for repairs");
        ArgumentCaptor<MonthClose> saved = ArgumentCaptor.forClass(MonthClose.class);
        verify(monthCloseRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(MonthCloseStatus.CLOSED);
        assertThat(saved.getValue().getCurrentVersion()).isEqualTo(1);
        ArgumentCaptor<MonthCloseVersion> version = ArgumentCaptor.forClass(MonthCloseVersion.class);
        verify(versionRepository).save(version.capture());
        assertThat(version.getValue().getVersion()).isEqualTo(1);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.MONTH_CLOSED), anyString(), anyString(), anyString(), eq(owner));
    }

    @Test
    void excuseForAnApprovedDayIsNotListedAsAnException() {
        List<MonthCloseRequests.ExceptionDay> excuses = new ArrayList<>(closeWithBothExceptions().exceptions());
        excuses.add(new MonthCloseRequests.ExceptionDay(MONTH.atDay(1), "Already approved"));

        service.close(PUMP_ID, MONTH, new MonthCloseRequests.Close(excuses), owner);

        ArgumentCaptor<List<MonthSummary.ExceptionDay>> exceptions = ArgumentCaptor.forClass(List.class);
        verify(summaryBuilder).build(eq(PUMP_ID), eq(MONTH), anyList(), anyInt(), exceptions.capture(), any());
        assertThat(exceptions.getValue()).extracting(MonthSummary.ExceptionDay::date)
                .containsExactly(UNCLOSED_DAY, UNAPPROVED_DAY);
    }

    @Test
    void reopenedMonthClosedAgainAddsTheNextVersionButAClosedOneIsRefused() {
        MonthClose close = MonthClose.builder().id(30L).pumpId(PUMP_ID).monthStart(MONTH.atDay(1))
                .status(MonthCloseStatus.CLOSED).currentVersion(1).build();
        when(monthCloseRepository.findByPumpIdAndMonthStart(PUMP_ID, MONTH.atDay(1))).thenReturn(Optional.of(close));

        assertThatThrownBy(() -> service.close(PUMP_ID, MONTH, closeWithBothExceptions(), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.MONTH_ALREADY_CLOSED));

        close.setStatus(MonthCloseStatus.REOPENED);
        service.close(PUMP_ID, MONTH, closeWithBothExceptions(), owner);

        assertThat(close.getCurrentVersion()).isEqualTo(2);
        assertThat(close.getStatus()).isEqualTo(MonthCloseStatus.CLOSED);
    }

    @Test
    void monthNotYetOverCannotBeClosed() {
        assertThatThrownBy(() -> service.close(PUMP_ID, YearMonth.of(2026, 11), closeWithBothExceptions(), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.MONTH_NOT_OVER));
    }

    @Test
    void reopeningRecordsTheReasonOnTheCurrentVersionAndRefusesAMonthNotClosed() {
        MonthClose close = MonthClose.builder().id(30L).pumpId(PUMP_ID).monthStart(MONTH.atDay(1))
                .status(MonthCloseStatus.CLOSED).currentVersion(1).build();
        MonthCloseVersion version = MonthCloseVersion.builder().monthCloseId(30L).version(1).closedByUserId(1L).build();
        when(monthCloseRepository.findByPumpIdAndMonthStart(PUMP_ID, MONTH.atDay(1))).thenReturn(Optional.of(close));
        when(versionRepository.findByMonthCloseIdAndVersion(30L, 1)).thenReturn(Optional.of(version));

        service.reopen(PUMP_ID, MONTH, new MonthCloseRequests.Reopen(" Supplier bill arrived late "), owner);

        assertThat(close.getStatus()).isEqualTo(MonthCloseStatus.REOPENED);
        assertThat(version.getReopenReason()).isEqualTo("Supplier bill arrived late");
        assertThat(version.getReopenedByUserId()).isEqualTo(owner.getId());
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.MONTH_REOPENED), anyString(), anyString(), anyString(), eq(owner));
        assertThatThrownBy(() -> service.reopen(PUMP_ID, MONTH, new MonthCloseRequests.Reopen("Again"), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.MONTH_NOT_CLOSED));
    }

    private static MonthCloseRequests.Close closeWithBothExceptions() {
        return new MonthCloseRequests.Close(List.of(
                new MonthCloseRequests.ExceptionDay(UNCLOSED_DAY, " Pump shut for repairs "),
                new MonthCloseRequests.ExceptionDay(UNAPPROVED_DAY, "Owner travelling, approves later")));
    }

    private static List<DayClose> daysClosedExceptOneUnclosedAndOneUnapproved() {
        Map<LocalDate, DayCloseStatus> exceptions = Map.of(UNAPPROVED_DAY, DayCloseStatus.CLOSED);
        return MONTH.atDay(1).datesUntil(MONTH.atEndOfMonth().plusDays(1))
                .filter(day -> !day.equals(UNCLOSED_DAY))
                .map(day -> DayClose.builder().pumpId(PUMP_ID).businessDate(day)
                        .status(exceptions.getOrDefault(day, DayCloseStatus.APPROVED)).build())
                .toList();
    }
}
