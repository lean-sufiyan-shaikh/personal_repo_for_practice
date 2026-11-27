package com.ppms.pump;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PumpShiftDefinitionServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 6);

    private final PumpShiftDefinitionRepository repository = mock(PumpShiftDefinitionRepository.class);
    private final PumpShiftDefinitionSupportService supportService = mock(PumpShiftDefinitionSupportService.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);

    private final PumpShiftDefinition night = definition("Night", "21:00", "08:59", true, LocalDate.of(2026, 1, 1), null);
    private final PumpShiftDefinition morning = definition("Morning", "09:00", "20:59", false, LocalDate.of(2026, 1, 1), null);

    @Test
    void openingTenMinutesBeforeTheNextWindowSuggestsTheNextWindowSoTheShiftIsNotInTheEndingOne() {
        CurrentShiftWindow window = serviceAt("08:50").windowForOpening(PUMP_ID, null, null);

        assertThat(window.definition()).isSameAs(morning);
        assertThat(window.shiftDate()).isEqualTo(TODAY);
    }

    @Test
    void withinTheGraceTheManagerMayStillChooseTheEndingWindow() {
        CurrentShiftWindow window = serviceAt("08:50").windowForOpening(PUMP_ID, night.getId(), TODAY.minusDays(1));

        assertThat(window.definition()).isSameAs(night);
        assertThat(window.shiftDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void bothWindowsAreOfferedWithinTheGraceWithTheNextOneSuggested() {
        List<OpenableShiftWindowResponse> windows = serviceAt("08:50").getOpenableWindows(PUMP_ID);

        assertThat(windows).extracting(OpenableShiftWindowResponse::name).containsExactly("Night", "Morning");
        assertThat(windows).extracting(OpenableShiftWindowResponse::startsLater).containsExactly(false, true);
        assertThat(windows).extracting(OpenableShiftWindowResponse::suggested).containsExactly(false, true);
        assertThat(windows.get(1).endTime()).isEqualTo(LocalTime.parse("21:00"));
    }

    @Test
    void theNextWindowCannotBeChosenBeforeTheGraceStarts() {
        PumpShiftDefinitionService service = serviceAt("08:20");

        assertThat(service.windowForOpening(PUMP_ID, null, null).definition()).isSameAs(night);
        assertThatThrownBy(() -> service.windowForOpening(PUMP_ID, morning.getId(), TODAY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("SHIFT_WINDOW_NOT_OPENABLE_NOW");
    }

    @Test
    void theGraceIsSetPerPumpAndZeroOffersOnlyTheCurrentWindow() {
        PumpShiftDefinitionService service = serviceAt("08:50");
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder()
                .id(PUMP_ID).earlyStartGraceMinutes(0).build()));

        assertThat(service.getOpenableWindows(PUMP_ID)).extracting(OpenableShiftWindowResponse::name).containsExactly("Night");
        assertThat(service.windowForOpening(PUMP_ID, null, null).definition()).isSameAs(night);
    }

    @Test
    void aGapBeforeTheNextWindowOpensTheNextWindowWithinTheGrace() {
        PumpShiftDefinition day = definition("Day", "06:00", "13:59", false, LocalDate.of(2026, 1, 1), null);
        when(repository.findActiveForPumpOnDate(eq(PUMP_ID), any())).thenReturn(List.of(day));
        OffsetDateTime early = LocalDateTime.of(TODAY, LocalTime.parse("05:45")).atZone(IST).toOffsetDateTime();
        PumpShiftDefinitionService service = new PumpShiftDefinitionService(repository, mock(ShiftRepository.class),
                supportService, fixedClock(early), pumpLocationRepository);

        CurrentShiftWindow window = service.windowForOpening(PUMP_ID, null, null);

        assertThat(window.definition()).isSameAs(day);
        assertThat(window.shiftDate()).isEqualTo(TODAY);
    }

    @Test
    void nightShiftOpenedAfterMidnightBelongsToThePreviousDay() {
        CurrentShiftWindow window = currentWindowAt("00:30");

        assertThat(window.definition()).isSameAs(night);
        assertThat(window.shiftDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void nightShiftOpenedBeforeMidnightBelongsToToday() {
        CurrentShiftWindow window = currentWindowAt("22:15");

        assertThat(window.definition()).isSameAs(night);
        assertThat(window.shiftDate()).isEqualTo(TODAY);
    }

    @Test
    void theMinuteBeforeTheBoundaryStillOpensTheEndingShift() {
        CurrentShiftWindow window = currentWindowAt("08:59:30");

        assertThat(window.definition()).isSameAs(night);
        assertThat(window.shiftDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void theBoundaryMinuteOpensTheNextShift() {
        CurrentShiftWindow window = currentWindowAt("09:00");

        assertThat(window.definition()).isSameAs(morning);
        assertThat(window.shiftDate()).isEqualTo(TODAY);
    }

    @Test
    void afterMidnightPartIsTakenOnlyFromYesterdaysSchedule() {
        PumpShiftDefinition yesterdaysNight = definition("Old night", "22:00", "05:59", true, LocalDate.of(2026, 1, 1), TODAY.minusDays(1));
        PumpShiftDefinition todaysNight = definition("New night", "23:00", "06:59", true, TODAY, null);
        PumpShiftDefinition todaysDay = definition("New day", "07:00", "22:59", false, TODAY, null);
        when(repository.findActiveForPumpOnDate(PUMP_ID, TODAY.minusDays(1))).thenReturn(List.of(yesterdaysNight));
        when(repository.findActiveForPumpOnDate(PUMP_ID, TODAY)).thenReturn(List.of(todaysNight, todaysDay));
        OffsetDateTime sixThirty = LocalDateTime.of(TODAY, LocalTime.parse("06:30")).atZone(IST).toOffsetDateTime();
        PumpShiftDefinitionService service =
                new PumpShiftDefinitionService(repository, mock(ShiftRepository.class), supportService, fixedClock(sixThirty),
                        pumpLocationRepository);
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder()
                .id(PUMP_ID).earlyStartGraceMinutes(0).build()));

        assertThatThrownBy(() -> service.windowForOpening(PUMP_ID, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not fall within any configured shift window");
    }

    @Test
    void groupOverlappingALaterBoundedGroupIsRejected() {
        PumpShiftDefinitionService service = serviceAt("10:00");
        PumpShiftDefinition laterGroup = definition("Day", "09:00", "20:59", false,
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 31));
        stubNewGroup(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10));
        when(repository.findOverlappingDefinitions(PUMP_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10)))
                .thenReturn(List.of(laterGroup));

        assertThatThrownBy(() -> service.createBatch(PUMP_ID, List.of(new CreateShiftDefinitionRequest()), user()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("overlaps the schedule 2026-01-05 – 2026-01-31");
    }

    @Test
    void groupDisabledOnItsStartDayCannotStayActiveAlongsideItsReplacement() {
        PumpShiftDefinitionService service = serviceAt("10:00");
        LocalDate day = LocalDate.of(2026, 1, 15);
        PumpShiftDefinition disabledSameDay = definition("Day", "09:00", "20:59", false, day, day);
        stubNewGroup(day, null);
        when(repository.findOverlappingDefinitions(eq(PUMP_ID), eq(day), any())).thenReturn(List.of(disabledSameDay));

        assertThatThrownBy(() -> service.createBatch(PUMP_ID, List.of(new CreateShiftDefinitionRequest()), user()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Delete that schedule");
    }

    @Test
    void earlierDisabledGroupEndingInsideTheNewRangeIsShortened() {
        PumpShiftDefinitionService service = serviceAt("10:00");
        LocalDate newStart = LocalDate.of(2026, 1, 15);
        PumpShiftDefinition earlier = definition("Day", "09:00", "20:59", false, LocalDate.of(2026, 1, 1), newStart);
        stubNewGroup(newStart, null);
        when(repository.findOverlappingDefinitions(eq(PUMP_ID), eq(newStart), any())).thenReturn(List.of(earlier));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createBatch(PUMP_ID, List.of(dayShiftRequest()), user());

        assertThat(earlier.getEffectiveTo()).isEqualTo(newStart.minusDays(1));
        verify(repository).saveAll(List.of(earlier));
    }

    private void stubNewGroup(LocalDate effectiveFrom, LocalDate effectiveTo) {
        when(supportService.maxShiftsPerPump()).thenReturn(4);
        when(supportService.resolveEffectiveFrom(anyList())).thenReturn(effectiveFrom);
        when(supportService.resolveEffectiveTo(anyList())).thenReturn(effectiveTo);
    }

    /** The window a shift opened at this time belongs to when the pump allows no early start. */
    private CurrentShiftWindow currentWindowAt(String time) {
        PumpShiftDefinitionService service = serviceAt(time);
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder()
                .id(PUMP_ID).earlyStartGraceMinutes(0).build()));
        return service.windowForOpening(PUMP_ID, null, null);
    }

    private PumpShiftDefinitionService serviceAt(String time) {
        when(repository.findActiveForPumpOnDate(eq(PUMP_ID), any())).thenReturn(List.of(night, morning));
        OffsetDateTime now = LocalDateTime.of(TODAY, LocalTime.parse(time)).atZone(IST).toOffsetDateTime();
        return new PumpShiftDefinitionService(repository, mock(ShiftRepository.class), supportService, fixedClock(now),
                pumpLocationRepository);
    }

    private BusinessClock fixedClock(OffsetDateTime now) {
        return new BusinessClock() {
            @Override public ZoneId zone() { return IST; }
            @Override public OffsetDateTime now() { return now; }
            @Override public LocalDate today() { return now.toLocalDate(); }
            @Override public OffsetDateTime startOfDay(LocalDate date) { return date.atStartOfDay(IST).toOffsetDateTime(); }
        };
    }

    private PumpShiftDefinition definition(String name, String start, String end, boolean crossesMidnight,
                                           LocalDate effectiveFrom, LocalDate effectiveTo) {
        return PumpShiftDefinition.builder()
                .id((long) name.hashCode())
                .pumpId(PUMP_ID)
                .name(name)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .crossesMidnight(crossesMidnight)
                .isNightShift(crossesMidnight)
                .sortOrder(crossesMidnight ? 1 : 2)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .build();
    }

    private CreateShiftDefinitionRequest dayShiftRequest() {
        CreateShiftDefinitionRequest request = new CreateShiftDefinitionRequest();
        request.setName("Day");
        request.setStartTime(LocalTime.parse("09:00"));
        request.setEndTime(LocalTime.parse("08:59"));
        request.setNightShift(true);
        request.setSortOrder(1);
        return request;
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        return user;
    }
}
