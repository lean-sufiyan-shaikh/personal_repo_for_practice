package com.ppms.collection;

import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pumps/{pumpId}/collection-settings")
@RequiredArgsConstructor
public class CollectionSettingsController {

    private final CollectionSettingsService settingsService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public CollectionSettingsResponse getSettings(@PathVariable Long pumpId) {
        return settingsService.settingsOf(pumpId);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public CollectionSettingsResponse updateSettings(@PathVariable Long pumpId,
                                                     @Valid @RequestBody UpdateCollectionSettingsRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        return settingsService.update(pumpId, request, currentUser);
    }
}
