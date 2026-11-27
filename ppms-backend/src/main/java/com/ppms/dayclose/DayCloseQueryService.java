package com.ppms.dayclose;

import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.report.BalanceSheet;
import com.ppms.report.BalanceSheetRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** The close preview of a day, the list of closed days and one closed day with its versions and comments. */
@Service
@RequiredArgsConstructor
public class DayCloseQueryService {

    private static final String UNKNOWN_USER = "Unknown";

    private final DayCloseRepository dayCloseRepository;
    private final DayCloseVersionRepository versionRepository;
    private final DayCloseCommentRepository commentRepository;
    private final DayFiguresReader figuresReader;
    private final BalanceSheetRepository balanceSheetRepository;
    private final UserRepository userRepository;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public DayClosePreviewResponse preview(Long pumpId, LocalDate date, User currentUser) {
        DayCloseService.requireNotInFuture(date, businessClock.today());
        DayCloseStatus status = dayCloseRepository.findByPumpIdAndBusinessDate(pumpId, date)
                .map(DayClose::getStatus)
                .orElse(null);
        DayFigures figures = figuresReader.read(pumpId, date, currentUser);
        List<ChecklistItem> checks = DayChecklist.checksFor(figures);
        boolean canClose = (status == null || status == DayCloseStatus.REOPENED)
                && checks.stream().noneMatch(ChecklistItem::isMissingBlockingItem);
        return new DayClosePreviewResponse(date, status, canClose, checks, DaySummaryBuilder.build(figures, businessClock.now()));
    }

    @Transactional(readOnly = true)
    public PagedResponse<DayCloseListItemResponse> list(Long pumpId, DayCloseStatus status, int page, int size) {
        Page<DayClose> closes = status == null
                ? dayCloseRepository.findByPumpIdOrderByBusinessDateDesc(pumpId, PageRequests.of(page, size))
                : dayCloseRepository.findByPumpIdAndStatusOrderByBusinessDateDesc(pumpId, status, PageRequests.of(page, size));
        Set<Long> ids = closes.getContent().stream().map(DayClose::getId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return PagedResponse.of(closes.map(close -> toListItem(close, null, Map.of(), Map.of())));
        }
        Map<Long, DaySummary> summaryByCloseId = versionRepository.findCurrentVersions(ids).stream()
                .collect(Collectors.toMap(DayCloseVersion::getDayCloseId, DayCloseVersion::getSummary));
        Map<Long, Long> commentCountByCloseId = commentRepository.countByDayCloseIds(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        Set<Long> userIds = new HashSet<>();
        closes.getContent().forEach(close -> {
            userIds.add(close.getClosedByUserId());
            userIds.add(close.getApprovedByUserId());
        });
        Map<Long, String> nameById = namesOf(userIds);
        return PagedResponse.of(closes.map(close ->
                toListItem(close, summaryByCloseId.get(close.getId()), nameById, commentCountByCloseId)));
    }

    @Transactional(readOnly = true)
    public DayCloseDetailResponse detail(Long pumpId, LocalDate date) {
        return toDetail(findDayClose(pumpId, date));
    }

    @Transactional(readOnly = true)
    public DaySummary versionSummary(Long pumpId, LocalDate date, int version) {
        DayClose close = findDayClose(pumpId, date);
        return versionRepository.findByDayCloseIdAndVersion(close.getId(), version)
                .map(DayCloseVersion::getSummary)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.DAY_CLOSE,
                        "Version " + version + " of the close of " + date + " not found"));
    }

    DayClose findDayClose(Long pumpId, LocalDate date) {
        return dayCloseRepository.findByPumpIdAndBusinessDate(pumpId, date)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.DAY_CLOSE, date + " has not been closed"));
    }

    DayCloseDetailResponse toDetail(DayClose close) {
        List<DayCloseVersion> versions = versionRepository.findByDayCloseIdOrderByVersionDesc(close.getId());
        List<DayCloseComment> comments = commentRepository.findByDayCloseIdOrderByCreatedAtAscIdAsc(close.getId());
        Set<Long> userIds = new HashSet<>();
        userIds.add(close.getClosedByUserId());
        userIds.add(close.getApprovedByUserId());
        versions.forEach(version -> userIds.add(version.getClosedByUserId()));
        comments.forEach(comment -> userIds.add(comment.getAuthorUserId()));
        Map<Long, String> nameById = namesOf(userIds);
        DaySummary summary = versions.stream()
                .filter(version -> version.getVersion() == close.getCurrentVersion())
                .findFirst()
                .map(DayCloseVersion::getSummary)
                .orElseThrow(() -> new IllegalStateException("Day close " + close.getId() + " has no version "
                        + close.getCurrentVersion()));
        boolean sheetStale = close.getBalanceSheetId() != null && balanceSheetRepository.findById(close.getBalanceSheetId())
                .map(BalanceSheet::isStale)
                .orElse(false);
        return new DayCloseDetailResponse(close.getBusinessDate(), close.getStatus(), close.getCurrentVersion(),
                nameById.getOrDefault(close.getClosedByUserId(), UNKNOWN_USER), close.getClosedAt(),
                close.getApprovedByUserId() == null ? null : nameById.getOrDefault(close.getApprovedByUserId(), UNKNOWN_USER),
                close.getApprovedAt(), close.getBalanceSheetId(), sheetStale, summary,
                versions.stream()
                        .map(version -> new DayCloseDetailResponse.VersionItem(version.getVersion(),
                                nameById.getOrDefault(version.getClosedByUserId(), UNKNOWN_USER), version.getClosedAt()))
                        .toList(),
                comments.stream()
                        .map(comment -> new DayCloseDetailResponse.CommentItem(comment.getId(), comment.getKind(),
                                comment.getMessage(), nameById.getOrDefault(comment.getAuthorUserId(), UNKNOWN_USER),
                                comment.getCreatedAt()))
                        .toList());
    }

    private static DayCloseListItemResponse toListItem(DayClose close, DaySummary summary, Map<Long, String> nameById,
                                                       Map<Long, Long> commentCountByCloseId) {
        return new DayCloseListItemResponse(close.getBusinessDate(), close.getStatus(), close.getCurrentVersion(),
                nameById.getOrDefault(close.getClosedByUserId(), UNKNOWN_USER), close.getClosedAt(),
                close.getApprovedByUserId() == null ? null : nameById.getOrDefault(close.getApprovedByUserId(), UNKNOWN_USER),
                close.getApprovedAt(),
                summary == null ? null : summary.overallFlag(),
                summary == null ? null : summary.sales().flag().colour(),
                summary == null ? null : summary.cash().flag().colour(),
                summary == null ? null : summary.stock().flag().colour(),
                summary == null ? null : summary.credit().flag().colour(),
                summary == null ? null : summary.sales().fuelRevenue(),
                summary == null ? null : summary.sales().grossMargin(),
                commentCountByCloseId.getOrDefault(close.getId(), 0L));
    }

    private Map<Long, String> namesOf(Set<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (first, second) -> first));
    }
}
