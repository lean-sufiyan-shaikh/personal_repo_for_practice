package com.ppms.payroll;

import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cash advances to staff, recovered from their later payroll. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/staff-advances")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
public class StaffAdvanceController {

    private final StaffAdvanceService staffAdvanceService;

    /** GET /api/pumps/{pumpId}/staff-advances[?userId=] — advances newest first, with what each staff member still owes. */
    @GetMapping
    public StaffAdvanceResponses.AdvanceListResponse listAdvances(@PathVariable Long pumpId,
                                                                  @RequestParam(required = false) Long userId) {
        return staffAdvanceService.listAdvances(pumpId, userId);
    }

    /** POST /api/pumps/{pumpId}/staff-advances — gives an advance; its cash leaves the safe on the advance date. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffAdvanceResponses.AdvanceResponse giveAdvance(@PathVariable Long pumpId,
                                                             @Valid @RequestBody GiveStaffAdvanceRequest request,
                                                             @AuthenticationPrincipal User currentUser) {
        return staffAdvanceService.giveAdvance(pumpId, request, currentUser);
    }

    /** PATCH /api/pumps/{pumpId}/staff-advances/{advanceId}/void — voids an advance payroll has not taken from. */
    @PatchMapping("/{advanceId}/void")
    public StaffAdvanceResponses.AdvanceResponse voidAdvance(@PathVariable Long pumpId, @PathVariable Long advanceId,
                                                             @Valid @RequestBody VoidStaffAdvanceRequest request,
                                                             @AuthenticationPrincipal User currentUser) {
        return staffAdvanceService.voidAdvance(pumpId, advanceId, request.voidReason(), currentUser);
    }
}
