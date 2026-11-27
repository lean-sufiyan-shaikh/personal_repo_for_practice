package com.ppms.monthclose;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.DayClose;
import com.ppms.dayclose.DayCloseRepository;
import com.ppms.dayclose.DayCloseStatus;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Closes a month once it is over and every business day of it (from the day the pump was set up) is closed and
 * approved, or listed as an exception with a reason. Each close stores the month summary as a new version; while the
 * month is closed, ClosedDayGuard refuses writes and day re-opens on its days until the owner re-opens it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthCloseService {

    private static final String ENTITY_NAME = "MonthClose";
    private static final String NOT_CLOSED = "NOT_CLOSED";

    private final MonthCloseRepository monthCloseRepository;
    private final MonthCloseVersionRepository versionRepository;
    private final DayCloseRepository dayCloseRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final MonthSummaryBuilder summaryBuilder;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public MonthCloseResponses.Detail detail(Long pumpId, YearMonth month) {
        PumpLocation pump = findPump(pumpId);
        return toDetail(pumpId, month, businessDays(pump, month),
                monthCloseRepository.findByPumpIdAndMonthStart(pumpId, month.atDay(1)).orElse(null));
    }

    @Transactional(readOnly = true)
    public PagedResponse<MonthCloseResponses.ListItem> list(Long pumpId, int page, int size) {
        return PagedResponse.of(monthCloseRepository.findByPumpIdOrderByMonthStartDesc(pumpId, PageRequests.of(page, size))
                .map(close -> new MonthCloseResponses.ListItem(YearMonth.from(close.getMonthStart()).toString(),
                        close.getStatus(), close.getCurrentVersion())));
    }

    @Transactional(readOnly = true)
    public MonthSummary versionSummary(Long pumpId, YearMonth month, int version) {
        MonthClose close = findMonthClose(pumpId, month);
        return versionRepository.findByMonthCloseIdAndVersion(close.getId(), version)
                .map(MonthCloseVersion::getSummary)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.MONTH_CLOSE,
                        "Version " + version + " of " + month + " not found"));
    }

    /** The pump row is locked so two closes of the same month cannot both pass the checks and save a version. */
    @Transactional
    public MonthCloseResponses.Detail close(Long pumpId, YearMonth month, MonthCloseRequests.Close request, User currentUser) {
        if (!month.atEndOfMonth().isBefore(businessClock.today())) {
            throw new BusinessException(ErrorCodes.MONTH_NOT_OVER, Map.of("month", month.toString()),
                    month + " is not over yet; a month can be closed from the first day of the next month.");
        }
        PumpLocation pump = pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        MonthClose existing = monthCloseRepository.findByPumpIdAndMonthStart(pumpId, month.atDay(1)).orElse(null);
        if (existing != null && existing.getStatus() == MonthCloseStatus.CLOSED) {
            throw new BusinessException(ErrorCodes.MONTH_ALREADY_CLOSED, Map.of("month", month.toString()),
                    month + " is already closed.");
        }
        List<LocalDate> businessDays = businessDays(pump, month);
        Map<LocalDate, String> statusByDay = dayStatuses(pumpId, month);
        Map<LocalDate, String> reasonByDay = exceptionReasons(month, businessDays, request.exceptionsOrEmpty());
        List<LocalDate> notReady = businessDays.stream()
                .filter(day -> !isApproved(statusByDay, day) && !reasonByDay.containsKey(day))
                .toList();
        if (!notReady.isEmpty()) {
            throw new BusinessException(ErrorCodes.MONTH_DAYS_NOT_READY,
                    Map.of("month", month.toString(), "count", notReady.size(),
                            "dates", notReady.stream().map(LocalDate::toString).toList()),
                    notReady.size() + " day(s) of " + month + " are not closed and approved, starting with "
                            + notReady.get(0) + ". Close and approve them or list them as exceptions with a reason.");
        }

        OffsetDateTime now = businessClock.now();
        List<MonthSummary.ExceptionDay> exceptions = businessDays.stream()
                .filter(day -> reasonByDay.containsKey(day) && !isApproved(statusByDay, day))
                .map(day -> new MonthSummary.ExceptionDay(day, statusByDay.getOrDefault(day, NOT_CLOSED), reasonByDay.get(day)))
                .toList();
        MonthSummary summary = summaryBuilder.build(pumpId, month, businessDays,
                (int) businessDays.stream().filter(day -> isApproved(statusByDay, day)).count(), exceptions, now);
        MonthClose close = existing != null ? existing
                : MonthClose.builder().pumpId(pumpId).monthStart(month.atDay(1)).build();
        close.setCurrentVersion(close.getCurrentVersion() + 1);
        close.setStatus(MonthCloseStatus.CLOSED);
        close = monthCloseRepository.save(close);
        versionRepository.save(MonthCloseVersion.builder()
                .monthCloseId(close.getId())
                .version(close.getCurrentVersion())
                .summary(summary)
                .closedByUserId(currentUser.getId())
                .closedAt(now)
                .build());

        auditService.log(pumpId, AuditAction.MONTH_CLOSED, ENTITY_NAME, close.getId().toString(),
                "Month " + month + " closed (version " + close.getCurrentVersion() + ", " + exceptions.size()
                        + " exception day(s), net profit ₹" + summary.netProfit() + ")", currentUser);
        log.info("Month closed: pump={}, month={}, version={}, exceptions={}, by={}", pumpId, month,
                close.getCurrentVersion(), exceptions.size(), currentUser.getId());
        return toDetail(pumpId, month, businessDays, close);
    }

    @Transactional
    public MonthCloseResponses.Detail reopen(Long pumpId, YearMonth month, MonthCloseRequests.Reopen request, User currentUser) {
        PumpLocation pump = pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        MonthClose close = findMonthClose(pumpId, month);
        if (close.getStatus() != MonthCloseStatus.CLOSED) {
            throw new BusinessException(ErrorCodes.MONTH_NOT_CLOSED, Map.of("month", month.toString()),
                    month + " is not closed.");
        }
        String reason = request.reason().trim();
        MonthCloseVersion version = versionRepository.findByMonthCloseIdAndVersion(close.getId(), close.getCurrentVersion())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.MONTH_CLOSE, "Current version not found"));
        version.setReopenedByUserId(currentUser.getId());
        version.setReopenedAt(businessClock.now());
        version.setReopenReason(reason);
        versionRepository.save(version);
        close.setStatus(MonthCloseStatus.REOPENED);
        monthCloseRepository.save(close);
        auditService.log(pumpId, AuditAction.MONTH_REOPENED, ENTITY_NAME, close.getId().toString(),
                "Month " + month + " re-opened (was version " + close.getCurrentVersion() + "): " + reason, currentUser);
        log.info("Month re-opened: pump={}, month={}, version={}, by={}", pumpId, month, close.getCurrentVersion(),
                currentUser.getId());
        return toDetail(pumpId, month, businessDays(pump, month), close);
    }

    private MonthCloseResponses.Detail toDetail(Long pumpId, YearMonth month, List<LocalDate> businessDays, MonthClose close) {
        Map<LocalDate, String> statusByDay = dayStatuses(pumpId, month);
        List<MonthCloseResponses.Day> days = businessDays.stream()
                .map(day -> new MonthCloseResponses.Day(day, statusByDay.getOrDefault(day, NOT_CLOSED),
                        "/dashboard/day-close?date=" + day))
                .toList();
        int notReady = (int) businessDays.stream().filter(day -> !isApproved(statusByDay, day)).count();
        boolean monthOver = month.atEndOfMonth().isBefore(businessClock.today());
        if (close == null) {
            return new MonthCloseResponses.Detail(month.toString(), month.atDay(1), month.atEndOfMonth(), monthOver, null,
                    null, days, notReady, null, List.of());
        }
        List<MonthCloseVersion> versions = versionRepository.findByMonthCloseIdOrderByVersionDesc(close.getId());
        Map<Long, String> names = namesOf(versions);
        MonthSummary current = versions.stream().filter(v -> v.getVersion() == close.getCurrentVersion())
                .findFirst().map(MonthCloseVersion::getSummary).orElse(null);
        return new MonthCloseResponses.Detail(month.toString(), month.atDay(1), month.atEndOfMonth(), monthOver,
                close.getStatus(), close.getCurrentVersion(), days, notReady, current,
                versions.stream().map(v -> new MonthCloseResponses.Version(v.getVersion(),
                        names.getOrDefault(v.getClosedByUserId(), "Unknown"), v.getClosedAt(),
                        v.getReopenedByUserId() != null ? names.getOrDefault(v.getReopenedByUserId(), "Unknown") : null,
                        v.getReopenedAt(), v.getReopenReason())).toList());
    }

    /** The month's days from the day the pump was set up; none when it was set up after the month. */
    private List<LocalDate> businessDays(PumpLocation pump, YearMonth month) {
        LocalDate setUpOn = pump.getCreatedAt().atZoneSameInstant(businessClock.zone()).toLocalDate();
        LocalDate first = setUpOn.isAfter(month.atDay(1)) ? setUpOn : month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        return first.isAfter(last) ? List.of() : first.datesUntil(last.plusDays(1)).toList();
    }

    private Map<LocalDate, String> dayStatuses(Long pumpId, YearMonth month) {
        return dayCloseRepository.findByPumpIdAndBusinessDateBetween(pumpId, month.atDay(1), month.atEndOfMonth()).stream()
                .collect(Collectors.toMap(DayClose::getBusinessDate, day -> day.getStatus().name()));
    }

    private static Map<LocalDate, String> exceptionReasons(YearMonth month, List<LocalDate> businessDays,
                                                           List<MonthCloseRequests.ExceptionDay> exceptions) {
        Set<LocalDate> days = Set.copyOf(businessDays);
        Map<LocalDate, String> reasons = new LinkedHashMap<>();
        for (MonthCloseRequests.ExceptionDay exception : exceptions) {
            if (!days.contains(exception.date())) {
                throw new BusinessException(ErrorCodes.MONTH_EXCEPTION_NOT_IN_MONTH,
                        Map.of("date", exception.date().toString(), "month", month.toString()),
                        exception.date() + " is not a business day of " + month + ".");
            }
            reasons.put(exception.date(), exception.reason().trim());
        }
        return reasons;
    }

    private static boolean isApproved(Map<LocalDate, String> statusByDay, LocalDate day) {
        return DayCloseStatus.APPROVED.name().equals(statusByDay.get(day));
    }

    private Map<Long, String> namesOf(List<MonthCloseVersion> versions) {
        Set<Long> userIds = versions.stream()
                .flatMap(v -> Stream.of(v.getClosedByUserId(), v.getReopenedByUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (first, second) -> first));
    }

    private MonthClose findMonthClose(Long pumpId, YearMonth month) {
        return monthCloseRepository.findByPumpIdAndMonthStart(pumpId, month.atDay(1))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.MONTH_CLOSE, month + " has not been closed"));
    }

    private PumpLocation findPump(Long pumpId) {
        return pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
    }
}
