package com.ppms.radar;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LeakageRadarController {

    private final LeakageRadarService radarService;

    /**
     * GET /api/pumps/{pumpId}/leakage-radar?days=30|90 — "worth a look" scores per person, nozzle and tank.
     * Owners and admins only: the scores can be about managers and operators.
     */
    @GetMapping("/api/pumps/{pumpId}/leakage-radar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<LeakageRadarResponse> getRadar(@PathVariable Long pumpId,
                                                         @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(radarService.radar(pumpId, days));
    }
}
