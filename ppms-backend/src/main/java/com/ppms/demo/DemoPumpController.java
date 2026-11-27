package com.ppms.demo;

import com.ppms.pump.PumpLocation;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** An owner creates or deletes their own demo pump; a super admin does it for any owner. */
@RestController
@RequiredArgsConstructor
public class DemoPumpController {

    private final DemoPumpService demoPumpService;

    public record DemoPumpResponse(Long pumpId, String pumpName) {
        static DemoPumpResponse of(PumpLocation pump) {
            return new DemoPumpResponse(pump.getId(), pump.getName());
        }
    }

    @PostMapping("/api/demo-pump")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<DemoPumpResponse> createMyDemoPump(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DemoPumpResponse.of(demoPumpService.createFor(currentUser.getId(), currentUser)));
    }

    @DeleteMapping("/api/demo-pump")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> deleteMyDemoPump(@AuthenticationPrincipal User currentUser) {
        demoPumpService.deleteFor(currentUser.getId(), currentUser);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/super-admin/owners/{ownerId}/demo-pump")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<DemoPumpResponse> createDemoPumpForOwner(@PathVariable Long ownerId,
                                                                   @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DemoPumpResponse.of(demoPumpService.createFor(ownerId, currentUser)));
    }

    @DeleteMapping("/api/super-admin/owners/{ownerId}/demo-pump")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteDemoPumpForOwner(@PathVariable Long ownerId,
                                                       @AuthenticationPrincipal User currentUser) {
        demoPumpService.deleteFor(ownerId, currentUser);
        return ResponseEntity.noContent().build();
    }
}
