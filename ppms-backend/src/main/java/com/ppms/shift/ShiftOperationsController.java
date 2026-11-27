package com.ppms.shift;

import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Work on an open shift without closing it: the blind cash count, more operators, nozzle swaps and relief breaks.
 * Under /api/pumps/{pumpId}/shifts so the PumpAccessInterceptor checks the pump on every request.
 */
@RestController
@RequestMapping("/api/pumps/{pumpId}/shifts/{shiftId}")
@RequiredArgsConstructor
public class ShiftOperationsController {

    private final ShiftOperationsReadService operationsReadService;
    private final ShiftBlindCountService blindCountService;
    private final ShiftOperatorService shiftOperatorService;
    private final NozzleTurnService nozzleTurnService;
    private final ShiftReliefService reliefService;

    /** GET /api/pumps/{pumpId}/shifts/{shiftId}/operations — operators, reliefs, shares and timeline of the shift. */
    @GetMapping("/operations")
    public ShiftOperationsResponse operations(@PathVariable Long pumpId, @PathVariable Long shiftId,
                                              @AuthenticationPrincipal User currentUser) {
        return operationsReadService.operationsOf(pumpId, shiftId, currentUser);
    }

    /** POST /api/pumps/{pumpId}/shifts/{shiftId}/blind-count — records the collections before the expected amount is shown. */
    @PostMapping("/blind-count")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ShiftResponse recordBlindCount(@PathVariable Long pumpId, @PathVariable Long shiftId,
                                          @Valid @RequestBody RecordBlindCountRequest request,
                                          @AuthenticationPrincipal User currentUser) {
        return blindCountService.recordBlindCount(pumpId, shiftId, request, currentUser);
    }

    /** POST /api/pumps/{pumpId}/shifts/{shiftId}/operators — another operator joins the shift for the rest of it. */
    @PostMapping("/operators")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ShiftResponse addOperator(@PathVariable Long pumpId, @PathVariable Long shiftId,
                                     @Valid @RequestBody AddShiftOperatorRequest request,
                                     @AuthenticationPrincipal User currentUser) {
        return shiftOperatorService.addOperator(pumpId, shiftId, request.operatorId(), currentUser);
    }

    /** DELETE /api/pumps/{pumpId}/shifts/{shiftId}/operators/{operatorId} — an added operator who works no nozzle leaves. */
    @DeleteMapping("/operators/{operatorId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ShiftResponse removeOperator(@PathVariable Long pumpId, @PathVariable Long shiftId, @PathVariable Long operatorId,
                                        @AuthenticationPrincipal User currentUser) {
        return shiftOperatorService.removeOperator(pumpId, shiftId, operatorId, currentUser);
    }

    /** POST /api/pumps/{pumpId}/shifts/{shiftId}/nozzle-swaps — moves a nozzle to another operator at a meter reading. */
    @PostMapping("/nozzle-swaps")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ShiftResponse swapNozzle(@PathVariable Long pumpId, @PathVariable Long shiftId,
                                    @Valid @RequestBody SwapNozzleRequest request,
                                    @AuthenticationPrincipal User currentUser) {
        return nozzleTurnService.swapNozzle(pumpId, shiftId, request, currentUser);
    }

    /** POST /api/pumps/{pumpId}/shifts/{shiftId}/reliefs — a relief operator takes over an operator's nozzles for a break. */
    @PostMapping("/reliefs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ShiftResponse startRelief(@PathVariable Long pumpId, @PathVariable Long shiftId,
                                     @Valid @RequestBody StartReliefRequest request,
                                     @AuthenticationPrincipal User currentUser) {
        return reliefService.startRelief(pumpId, shiftId, request, currentUser);
    }

    /** POST /api/pumps/{pumpId}/shifts/{shiftId}/reliefs/{reliefId}/end — the operator takes their nozzles back. */
    @PostMapping("/reliefs/{reliefId}/end")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ShiftResponse endRelief(@PathVariable Long pumpId, @PathVariable Long shiftId, @PathVariable Long reliefId,
                                   @Valid @RequestBody EndReliefRequest request,
                                   @AuthenticationPrincipal User currentUser) {
        return reliefService.endRelief(pumpId, shiftId, reliefId, request, currentUser);
    }
}
