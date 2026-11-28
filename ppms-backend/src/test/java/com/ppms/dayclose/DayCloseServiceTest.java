package com.ppms.dayclose;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.report.BalanceSheetService;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.shift.Shift;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DayCloseServiceTest {

    private static final Long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 27, 22, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

    private final DayCloseRepository dayCloseRepository = mock(DayCloseRepository.class);
    private final DayCloseVersionRepository versionRepository = mock(DayCloseVersionRepository.class);
    private final DayCloseCommentRepository commentRepository = mock(DayCloseCommentRepository.class);
    private final DayCloseQueryService queryService = mock(DayCloseQueryService.class);
    private final DayFiguresReader figuresReader = mock(DayFiguresReader.class);
    private final BalanceSheetService balanceSheetService = mock(BalanceSheetService.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final NotificationDedupService dedupService = mock(NotificationDedupService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BusinessClock businessClock = mock(BusinessClock.class);

    private final DayCloseService service = new DayCloseService(dayCloseRepository, versionRepository, commentRepository,
            queryService, figuresReader, balanceSheetService, mock(BalanceSheetStalenessService.class),
            pumpLocationRepository, dedupService, auditService, businessClock, mock(ClosedDayGuard.class));

    private final User owner = user(1L, UserRole.OWNER);
    private final User admin = user(2L, UserRole.ADMIN);
    private final User manager = user(3L, UserRole.MANAGER);

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(TODAY);
        when(businessClock.now()).thenReturn(NOW);
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(new PumpLocation()));
        when(dayCloseRepository.findByPumpIdAndBusinessDate(PUMP_ID, TODAY)).thenReturn(Optional.empty());
        when(dayCloseRepository.save(any(DayClose.class))).thenAnswer(invocation -> {
            DayClose close = invocation.getArgument(0);
            if (close.getId() == null) {
                close.setId(50L);
            }
            return close;
        });
        when(commentRepository.save(any(DayCloseComment.class))).thenAnswer(invocation -> {
            DayCloseComment comment = invocation.getArgument(0);
            comment.setId(60L);
            return comment;
        });
        when(balanceSheetService.refreshDaySheetForDayClose(eq(PUMP_ID), eq(TODAY), any())).thenReturn(Optional.of(90L));
        when(figuresReader.read(eq(PUMP_ID), eq(TODAY), any())).thenReturn(DaySummaryBuilderTest.figures().businessDate(TODAY).build());
    }

    @Test
    void missingSafeCountIsRefusedWithTheDateAndEveryMissingCheck() {
        when(figuresReader.read(eq(PUMP_ID), eq(TODAY), any())).thenReturn(DaySummaryBuilderTest.figures()
                .businessDate(TODAY).safeCount(Optional.empty()).fuelTypesWithoutNextDayPrice(List.of(FuelType.PETROL)).build());

        assertThatThrownBy(() -> service.close(PUMP_ID, TODAY, null, manager))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_CLOSE_SAFE_COUNT_MISSING);
                    assertThat(error.getParams()).containsEntry("date", TODAY.toString())
                            .containsEntry("missingCodes", List.of(ChecklistItem.SAFE_COUNT, ChecklistItem.NEXT_DAY_PRICES));
                });
        verify(dayCloseRepository, never()).save(any());
    }

    @Test
    void missingNextDayPricesAreRefusedNamingTheFuel() {
        when(figuresReader.read(eq(PUMP_ID), eq(TODAY), any())).thenReturn(DaySummaryBuilderTest.figures()
                .businessDate(TODAY).fuelTypesWithoutNextDayPrice(List.of(FuelType.PETROL)).build());

        assertThatThrownBy(() -> service.close(PUMP_ID, TODAY, null, manager))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_CLOSE_PRICES_MISSING);
                    assertThat(error.getParams()).containsEntry("fuelTypeCodes", List.of("PETROL"));
                });
    }

    @Test
    void openShiftIsRefusedWithTheirCount() {
        when(figuresReader.read(eq(PUMP_ID), eq(TODAY), any())).thenReturn(DaySummaryBuilderTest.figures()
                .businessDate(TODAY).openShifts(List.of(new Shift())).build());

        assertThatThrownBy(() -> service.close(PUMP_ID, TODAY, null, manager))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_CLOSE_OPEN_SHIFTS);
                    assertThat(error.getParams()).containsEntry("count", 1L);
                });
    }

    @Test
    void pendingExpensesOfAPastDayAreRefusedAsPendingApprovals() {
        when(figuresReader.read(eq(PUMP_ID), eq(TODAY), any())).thenReturn(DaySummaryBuilderTest.figures()
                .businessDate(TODAY).pastDay(true)
                .pendingExpenses(new DayFigures.Total(1, new BigDecimal("5000.00"))).build());

        assertThatThrownBy(() -> service.close(PUMP_ID, TODAY, null, manager))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_CLOSE_PENDING_APPROVALS);
                    assertThat(error.getParams()).containsEntry("amount", "5000.00")
                            .containsEntry("missingCodes", List.of(ChecklistItem.PENDING_EXPENSES));
                });
    }

    @Test
    void managerClosesTheDayAsTheFirstVersionWaitingForApprovalAndTheOwnerIsTold() {
        service.close(PUMP_ID, TODAY, null, manager);

        DayClose saved = savedClose();
        assertThat(saved.getStatus()).isEqualTo(DayCloseStatus.CLOSED);
        assertThat(saved.getCurrentVersion()).isEqualTo(1);
        assertThat(saved.getBalanceSheetId()).isEqualTo(90L);
        assertThat(saved.getApprovedByUserId()).isNull();
        ArgumentCaptor<DayCloseVersion> version = ArgumentCaptor.forClass(DayCloseVersion.class);
        verify(versionRepository).save(version.capture());
        assertThat(version.getValue().getVersion()).isEqualTo(1);
        verify(dedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.DAY_CLOSED), eq("DAY_CLOSED:date:" + TODAY + ":1"),
                anyString(), anyString(), anyMap());
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.DAY_CLOSED), anyString(), anyString(), anyString(), eq(manager));
    }

    @Test
    void managerCannotCloseAndApproveInOneStep() {
        assertThatThrownBy(() -> service.close(PUMP_ID, TODAY, new CloseDayRequest(true), manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_CLOSE_APPROVE_NOT_ALLOWED));
    }

    @Test
    void ownerOrAdminClosingAndApprovingInOneStepSendsNoApprovalRequest() {
        service.close(PUMP_ID, TODAY, new CloseDayRequest(true), admin);

        DayClose saved = savedClose();
        assertThat(saved.getStatus()).isEqualTo(DayCloseStatus.APPROVED);
        assertThat(saved.getApprovedByUserId()).isEqualTo(admin.getId());
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.DAY_APPROVED), anyString(), anyString(), anyString(), eq(admin));
        verify(dedupService, never()).maybeInsert(any(), eq(NotificationType.DAY_CLOSED), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void futureDayCannotBeClosed() {
        assertThatThrownBy(() -> service.close(PUMP_ID, TODAY.plusDays(1), null, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_CLOSE_DATE_IN_FUTURE));
    }

    @Test
    void closedDayCannotBeClosedAgainButAReopenedOneAddsTheNextVersion() {
        DayClose close = existingClose(DayCloseStatus.CLOSED);
        when(dayCloseRepository.findByPumpIdAndBusinessDate(PUMP_ID, TODAY)).thenReturn(Optional.of(close));

        assertThatThrownBy(() -> service.close(PUMP_ID, TODAY, null, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_ALREADY_CLOSED));

        close.setStatus(DayCloseStatus.REOPENED);
        service.close(PUMP_ID, TODAY, null, owner);

        assertThat(savedClose().getCurrentVersion()).isEqualTo(2);
        assertThat(savedClose().getStatus()).isEqualTo(DayCloseStatus.CLOSED);
    }

    @Test
    void ownerApprovesAClosedDayOnlyOnce() {
        DayClose close = existingClose(DayCloseStatus.CLOSED);
        when(queryService.findDayClose(PUMP_ID, TODAY)).thenReturn(close);

        service.approve(PUMP_ID, TODAY, owner);

        assertThat(close.getStatus()).isEqualTo(DayCloseStatus.APPROVED);
        assertThat(close.getApprovedByUserId()).isEqualTo(owner.getId());
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.DAY_APPROVED), anyString(), anyString(), anyString(), eq(owner));
        assertThatThrownBy(() -> service.approve(PUMP_ID, TODAY, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_CLOSE_NOT_AWAITING_APPROVAL));
    }

    @Test
    void ownerAsksAQuestionAndTheManagerReplies() {
        when(queryService.findDayClose(PUMP_ID, TODAY)).thenReturn(existingClose(DayCloseStatus.CLOSED));

        service.comment(PUMP_ID, TODAY, new DayCloseCommentRequest(" Why was the tank not dipped? "), owner);
        service.comment(PUMP_ID, TODAY, new DayCloseCommentRequest("The dipstick was being repaired."), manager);

        ArgumentCaptor<DayCloseComment> comments = ArgumentCaptor.forClass(DayCloseComment.class);
        verify(commentRepository, times(2)).save(comments.capture());
        assertThat(comments.getAllValues()).extracting(DayCloseComment::getKind)
                .containsExactly(DayCloseCommentKind.QUESTION, DayCloseCommentKind.REPLY);
        assertThat(comments.getAllValues().get(0).getMessage()).isEqualTo("Why was the tank not dipped?");
        verify(dedupService, times(2)).maybeInsert(eq(PUMP_ID), eq(NotificationType.DAY_CLOSE_COMMENT),
                anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void reopeningKeepsTheReasonClearsTheApprovalAndRefusesADayNotClosed() {
        DayClose close = existingClose(DayCloseStatus.APPROVED);
        close.setApprovedByUserId(owner.getId());
        when(queryService.findDayClose(PUMP_ID, TODAY)).thenReturn(close);

        service.reopen(PUMP_ID, TODAY, new ReopenDayRequest("  Late expense bill arrived "), owner);

        assertThat(close.getStatus()).isEqualTo(DayCloseStatus.REOPENED);
        assertThat(close.getApprovedByUserId()).isNull();
        ArgumentCaptor<DayCloseComment> comment = ArgumentCaptor.forClass(DayCloseComment.class);
        verify(commentRepository).save(comment.capture());
        assertThat(comment.getValue().getKind()).isEqualTo(DayCloseCommentKind.REOPEN_REASON);
        assertThat(comment.getValue().getMessage()).isEqualTo("Late expense bill arrived");
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.DAY_REOPENED), anyString(), anyString(), description.capture(), eq(owner));
        assertThat(description.getValue()).contains("Late expense bill arrived");
        verify(dedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.DAY_REOPENED), anyString(), anyString(), anyString(), anyMap());

        assertThatThrownBy(() -> service.reopen(PUMP_ID, TODAY, new ReopenDayRequest("Again"), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.DAY_NOT_CLOSED));
    }

    @Test
    void dayNeverClosedIsNotFound() {
        DayCloseQueryService realQueryService = new DayCloseQueryService(dayCloseRepository, versionRepository,
                commentRepository, figuresReader, null, null, businessClock);

        assertThatThrownBy(() -> realQueryService.findDayClose(PUMP_ID, TODAY.minusDays(1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nextDayPriceCountsOnlyWhenSavedFromTheGivenMomentForEachFuelOfAnActiveTank() {
        OffsetDateTime setFrom = NOW.withHour(0);
        List<UndergroundTank> tanks = List.of(tank(FuelType.PETROL), tank(FuelType.DIESEL), tank(FuelType.PETROL));
        List<GlobalFuelPrice> prices = List.of(
                GlobalFuelPrice.builder().fuelType(FuelType.PETROL).effectiveFrom(setFrom.minusDays(2)).build(),
                GlobalFuelPrice.builder().fuelType(FuelType.DIESEL).effectiveFrom(setFrom).build());

        assertThat(DayFiguresReader.fuelTypesWithoutPriceSetFrom(tanks, prices, setFrom)).containsExactly(FuelType.PETROL);
        assertThat(DayFiguresReader.fuelTypesWithoutPriceSetFrom(tanks, List.of(), setFrom))
                .containsExactly(FuelType.PETROL, FuelType.DIESEL);
    }

    private DayClose savedClose() {
        ArgumentCaptor<DayClose> captor = ArgumentCaptor.forClass(DayClose.class);
        verify(dayCloseRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static DayClose existingClose(DayCloseStatus status) {
        return DayClose.builder().id(50L).pumpId(PUMP_ID).businessDate(TODAY).status(status).currentVersion(1)
                .closedByUserId(3L).closedAt(NOW).build();
    }

    private static UndergroundTank tank(FuelType fuelType) {
        return UndergroundTank.builder().fuelType(fuelType).status(TankStatus.ACTIVE).build();
    }

    private static User user(Long id, UserRole role) {
        return User.builder().id(id).role(role).fullName(role.name() + " name").build();
    }
}
