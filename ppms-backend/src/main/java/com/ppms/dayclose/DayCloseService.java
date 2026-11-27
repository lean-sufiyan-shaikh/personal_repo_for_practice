package com.ppms.dayclose;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.report.BalanceSheetService;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closes a business day, lets the owner approve it or ask about it, and re-opens it. Closing refreshes the day's DAY
 * balance sheet and stores the summary as a new version; while the day is closed, ClosedDayGuard refuses writes on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DayCloseService {

    private static final String ENTITY_NAME = "DayClose";

    private final DayCloseRepository dayCloseRepository;
    private final DayCloseVersionRepository versionRepository;
    private final DayCloseCommentRepository commentRepository;
    private final DayCloseQueryService queryService;
    private final DayFiguresReader figuresReader;
    private final BalanceSheetService balanceSheetService;
    private final PumpLocationRepository pumpLocationRepository;
    private final NotificationDedupService dedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    /** The pump row is locked so two closes of the same day cannot both pass the checks and save a version. */
    @Transactional
    public DayCloseDetailResponse close(Long pumpId, LocalDate date, CloseDayRequest request, User currentUser) {
        boolean approveNow = request != null && request.approveNow();
        if (approveNow && !canApprove(currentUser)) {
            throw new BusinessException(ErrorCodes.DAY_CLOSE_APPROVE_NOT_ALLOWED, Map.of(),
                    "Only an owner or admin can approve a day while closing it.");
        }
        requireNotInFuture(date, businessClock.today());
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        DayClose existing = dayCloseRepository.findByPumpIdAndBusinessDate(pumpId, date).orElse(null);
        if (existing != null && existing.getStatus().isLocked()) {
            throw new BusinessException(ErrorCodes.DAY_ALREADY_CLOSED,
                    Map.of("date", date.toString(), "statusCode", existing.getStatus().name()),
                    date + " is already closed.");
        }
        DayFigures figures = figuresReader.read(pumpId, date, currentUser);
        requireBlockingChecksDone(date, DayChecklist.checksFor(figures));

        Long sheetId = balanceSheetService.refreshDaySheetForDayClose(pumpId, date, currentUser).orElse(null);
        OffsetDateTime now = businessClock.now();
        DaySummary summary = DaySummaryBuilder.build(figures, now);
        DayClose close = existing != null ? existing : DayClose.builder().pumpId(pumpId).businessDate(date).build();
        close.setCurrentVersion(close.getCurrentVersion() + 1);
        close.setClosedByUserId(currentUser.getId());
        close.setClosedAt(now);
        close.setBalanceSheetId(sheetId);
        close.setStatus(approveNow ? DayCloseStatus.APPROVED : DayCloseStatus.CLOSED);
        close.setApprovedByUserId(approveNow ? currentUser.getId() : null);
        close.setApprovedAt(approveNow ? now : null);
        close = dayCloseRepository.save(close);
        versionRepository.save(DayCloseVersion.builder()
                .dayCloseId(close.getId())
                .version(close.getCurrentVersion())
                .summary(summary)
                .closedByUserId(currentUser.getId())
                .closedAt(now)
                .build());

        auditService.log(pumpId, AuditAction.DAY_CLOSED, ENTITY_NAME, close.getId().toString(),
                "Day " + date + " closed (version " + close.getCurrentVersion() + ", summary " + summary.overallFlag() + ")",
                currentUser);
        if (approveNow) {
            auditService.log(pumpId, AuditAction.DAY_APPROVED, ENTITY_NAME, close.getId().toString(),
                    "Day " + date + " approved while closing it", currentUser);
        } else {
            notifyClosed(close, summary, currentUser);
        }
        log.info("Day closed: pump={}, date={}, version={}, flag={}, approved={}, sheet={}, by={}", pumpId, date,
                close.getCurrentVersion(), summary.overallFlag(), approveNow, sheetId, currentUser.getId());
        return queryService.toDetail(close);
    }

    @Transactional
    public DayCloseDetailResponse approve(Long pumpId, LocalDate date, User currentUser) {
        DayClose close = queryService.findDayClose(pumpId, date);
        if (close.getStatus() != DayCloseStatus.CLOSED) {
            throw new BusinessException(ErrorCodes.DAY_CLOSE_NOT_AWAITING_APPROVAL,
                    Map.of("date", date.toString(), "statusCode", close.getStatus().name()),
                    date + " is not waiting for approval.");
        }
        close.setStatus(DayCloseStatus.APPROVED);
        close.setApprovedByUserId(currentUser.getId());
        close.setApprovedAt(businessClock.now());
        dayCloseRepository.save(close);
        auditService.log(pumpId, AuditAction.DAY_APPROVED, ENTITY_NAME, close.getId().toString(),
                "Day " + date + " approved (version " + close.getCurrentVersion() + ")", currentUser);
        log.info("Day approved: pump={}, date={}, version={}, by={}", pumpId, date, close.getCurrentVersion(), currentUser.getId());
        return queryService.toDetail(close);
    }

    /** From an owner or admin it is a question to the manager who closed the day; from a manager, a reply. */
    @Transactional
    public DayCloseDetailResponse comment(Long pumpId, LocalDate date, DayCloseCommentRequest request, User currentUser) {
        DayClose close = queryService.findDayClose(pumpId, date);
        DayCloseCommentKind kind = canApprove(currentUser) ? DayCloseCommentKind.QUESTION : DayCloseCommentKind.REPLY;
        DayCloseComment comment = commentRepository.save(DayCloseComment.builder()
                .dayCloseId(close.getId())
                .kind(kind)
                .message(request.message().trim())
                .authorUserId(currentUser.getId())
                .build());
        auditService.log(pumpId, AuditAction.DAY_CLOSE_COMMENTED, ENTITY_NAME, close.getId().toString(),
                (kind == DayCloseCommentKind.QUESTION ? "Question" : "Reply") + " on the close of " + date + ": "
                        + comment.getMessage(), currentUser);
        Map<String, Object> params = params(date, "authorName", currentUser);
        params.put("message", comment.getMessage());
        params.put("kindCode", kind.name());
        dedupService.maybeInsert(pumpId, NotificationType.DAY_CLOSE_COMMENT, dedupKey("DAY_CLOSE_COMMENT", date, comment.getId()),
                kind == DayCloseCommentKind.QUESTION ? "Question About Day Close" : "Reply About Day Close",
                currentUser.getFullName() + " wrote about the close of " + date + ": " + comment.getMessage(), params);
        log.info("Day close comment: pump={}, date={}, kind={}, by={}", pumpId, date, kind, currentUser.getId());
        return queryService.toDetail(close);
    }

    @Transactional
    public DayCloseDetailResponse reopen(Long pumpId, LocalDate date, ReopenDayRequest request, User currentUser) {
        DayClose close = queryService.findDayClose(pumpId, date);
        if (!close.getStatus().isLocked()) {
            throw new BusinessException(ErrorCodes.DAY_NOT_CLOSED,
                    Map.of("date", date.toString(), "statusCode", close.getStatus().name()),
                    date + " is not closed.");
        }
        String reason = request.reason().trim();
        close.setStatus(DayCloseStatus.REOPENED);
        close.setApprovedByUserId(null);
        close.setApprovedAt(null);
        dayCloseRepository.save(close);
        DayCloseComment comment = commentRepository.save(DayCloseComment.builder()
                .dayCloseId(close.getId())
                .kind(DayCloseCommentKind.REOPEN_REASON)
                .message(reason)
                .authorUserId(currentUser.getId())
                .build());
        auditService.log(pumpId, AuditAction.DAY_REOPENED, ENTITY_NAME, close.getId().toString(),
                "Day " + date + " re-opened (was version " + close.getCurrentVersion() + "): " + reason, currentUser);
        Map<String, Object> params = params(date, "reopenedByName", currentUser);
        params.put("reason", reason);
        dedupService.maybeInsert(pumpId, NotificationType.DAY_REOPENED, dedupKey("DAY_REOPENED", date, comment.getId()),
                "Closed Day Re-opened",
                currentUser.getFullName() + " re-opened " + date + ": " + reason, params);
        log.info("Day re-opened: pump={}, date={}, version={}, by={}", pumpId, date, close.getCurrentVersion(), currentUser.getId());
        return queryService.toDetail(close);
    }

    static void requireNotInFuture(LocalDate date, LocalDate today) {
        if (date.isAfter(today)) {
            throw new BusinessException(ErrorCodes.DAY_CLOSE_DATE_IN_FUTURE, Map.of("date", date.toString()),
                    "A day can only be closed once it has begun.");
        }
    }

    private static void requireBlockingChecksDone(LocalDate date, List<ChecklistItem> checks) {
        List<ChecklistItem> missing = checks.stream().filter(ChecklistItem::isMissingBlockingItem).toList();
        if (missing.isEmpty()) {
            return;
        }
        ChecklistItem first = missing.get(0);
        Map<String, Object> params = new LinkedHashMap<>(first.params());
        params.put("date", date.toString());
        params.put("missingCodes", missing.stream().map(ChecklistItem::code).toList());
        String code = ChecklistItem.SAFE_COUNT.equals(first.code())
                ? ErrorCodes.DAY_CLOSE_SAFE_COUNT_MISSING
                : ErrorCodes.DAY_CLOSE_PRICES_MISSING;
        throw new BusinessException(code, params, first.text() + ".");
    }

    private void notifyClosed(DayClose close, DaySummary summary, User closedBy) {
        Map<String, Object> params = params(close.getBusinessDate(), "closedByName", closedBy);
        params.put("flagCode", summary.overallFlag().name());
        dedupService.maybeInsert(close.getPumpId(), NotificationType.DAY_CLOSED,
                dedupKey("DAY_CLOSED", close.getBusinessDate(), close.getCurrentVersion()),
                "Day Closed — Waiting For Approval",
                closedBy.getFullName() + " closed " + close.getBusinessDate() + " (summary " + summary.overallFlag()
                        + "). Review and approve it.", params);
    }

    private static Map<String, Object> params(LocalDate date, String actorParam, User actor) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("date", date.toString());
        params.put(actorParam, actor.getFullName());
        return params;
    }

    private static String dedupKey(String type, LocalDate date, long sequence) {
        return type + ":date:" + date + ":" + sequence;
    }

    private static boolean canApprove(User user) {
        return user.getRole() == UserRole.OWNER || user.getRole() == UserRole.ADMIN;
    }
}
