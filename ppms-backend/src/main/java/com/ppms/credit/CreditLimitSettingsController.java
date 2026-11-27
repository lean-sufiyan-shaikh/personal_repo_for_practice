package com.ppms.credit;

import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The limit new clients start with, the highest limit a manager may set, and when an account is near its limit. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/credit-limit-settings")
@RequiredArgsConstructor
public class CreditLimitSettingsController {

    private final CreditLimitService creditLimitService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ResponseEntity<CreditLimitSettingsResponse> getSettings(@PathVariable Long pumpId) {
        return ResponseEntity.ok(creditLimitService.settingsOf(pumpId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<CreditLimitSettingsResponse> updateSettings(@PathVariable Long pumpId,
                                                                      @Valid @RequestBody CreditLimitSettingsRequest request,
                                                                      @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(creditLimitService.updateSettings(pumpId, request, currentUser));
    }
}
