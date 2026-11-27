package com.ppms.monthclose;

import com.ppms.common.dto.PagedResponse;
import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

/** Closing a month (owners and admins) and re-opening it (owners only). Months are written as yyyy-MM. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/month-closes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class MonthCloseController {

    private final MonthCloseService monthCloseService;

    /** GET /api/pumps/{pumpId}/month-closes?page=0&size=12 — closed and re-opened months, newest first. */
    @GetMapping
    public PagedResponse<MonthCloseResponses.ListItem> list(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return monthCloseService.list(pumpId, page, size);
    }

    /** GET /api/pumps/{pumpId}/month-closes/{month} — the checklist of days, the stored summary and its versions. */
    @GetMapping("/{month}")
    public MonthCloseResponses.Detail detail(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return monthCloseService.detail(pumpId, month);
    }

    /** GET /api/pumps/{pumpId}/month-closes/{month}/versions/{version} — the summary stored by an earlier close. */
    @GetMapping("/{month}/versions/{version}")
    public MonthSummary version(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @PathVariable int version) {
        return monthCloseService.versionSummary(pumpId, month, version);
    }

    /** POST /api/pumps/{pumpId}/month-closes/{month}/close — body lists exception days with reasons. */
    @PostMapping("/{month}/close")
    public MonthCloseResponses.Detail close(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @Valid @RequestBody(required = false) MonthCloseRequests.Close request,
            @AuthenticationPrincipal User currentUser) {
        return monthCloseService.close(pumpId, month,
                request != null ? request : new MonthCloseRequests.Close(null), currentUser);
    }

    /** POST /api/pumps/{pumpId}/month-closes/{month}/reopen — owners only; the reason is audited. */
    @PostMapping("/{month}/reopen")
    @PreAuthorize("hasRole('OWNER')")
    public MonthCloseResponses.Detail reopen(
            @PathVariable Long pumpId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @Valid @RequestBody MonthCloseRequests.Reopen request,
            @AuthenticationPrincipal User currentUser) {
        return monthCloseService.reopen(pumpId, month, request, currentUser);
    }
}
