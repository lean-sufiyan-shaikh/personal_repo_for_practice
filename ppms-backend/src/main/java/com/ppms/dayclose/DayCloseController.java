package com.ppms.dayclose;

import com.ppms.common.dto.PagedResponse;
import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** Closing a business day (managers, admins, owners), approving or questioning it and re-opening it (owners, admins). */
@RestController
@RequestMapping("/api/pumps/{pumpId}/day-closes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
public class DayCloseController {

    private final DayCloseService dayCloseService;
    private final DayCloseQueryService queryService;

    /** GET /api/pumps/{pumpId}/day-closes/preview?date= — the checklist and the summary the close would store. */
    @GetMapping("/preview")
    public DayClosePreviewResponse preview(
            @PathVariable Long pumpId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal User currentUser) {
        return queryService.preview(pumpId, date, currentUser);
    }

    /** GET /api/pumps/{pumpId}/day-closes?status=&page=0&size=10 — closed days, newest first. */
    @GetMapping
    public PagedResponse<DayCloseListItemResponse> list(
            @PathVariable Long pumpId,
            @RequestParam(required = false) DayCloseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return queryService.list(pumpId, status, page, size);
    }

    /** GET /api/pumps/{pumpId}/day-closes/{date} — the closed day with its summary, versions and comments. */
    @GetMapping("/{date}")
    public DayCloseDetailResponse detail(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queryService.detail(pumpId, date);
    }

    /** GET /api/pumps/{pumpId}/day-closes/{date}/versions/{version} — the summary stored by an earlier close. */
    @GetMapping("/{date}/versions/{version}")
    public DaySummary version(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable int version) {
        return queryService.versionSummary(pumpId, date, version);
    }

    /** POST /api/pumps/{pumpId}/day-closes/{date}/close — closes (or re-closes) the day; approve=true for owners and admins. */
    @PostMapping("/{date}/close")
    public DayCloseDetailResponse close(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody(required = false) CloseDayRequest request,
            @AuthenticationPrincipal User currentUser) {
        return dayCloseService.close(pumpId, date, request, currentUser);
    }

    /** POST /api/pumps/{pumpId}/day-closes/{date}/approve */
    @PostMapping("/{date}/approve")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public DayCloseDetailResponse approve(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal User currentUser) {
        return dayCloseService.approve(pumpId, date, currentUser);
    }

    /** POST /api/pumps/{pumpId}/day-closes/{date}/comments — a question from an owner or admin, a reply from a manager. */
    @PostMapping("/{date}/comments")
    public DayCloseDetailResponse comment(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody DayCloseCommentRequest request,
            @AuthenticationPrincipal User currentUser) {
        return dayCloseService.comment(pumpId, date, request, currentUser);
    }

    /** POST /api/pumps/{pumpId}/day-closes/{date}/reopen — takes writes on the day again; the reason is audited. */
    @PostMapping("/{date}/reopen")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public DayCloseDetailResponse reopen(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody ReopenDayRequest request,
            @AuthenticationPrincipal User currentUser) {
        return dayCloseService.reopen(pumpId, date, request, currentUser);
    }
}
