package com.ppms.invoice;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/pumps/{pumpId}/reports/gst-summary?month=YYYY-MM — the month's output tax, input tax and net. */
@RestController
@RequiredArgsConstructor
public class GstSummaryController {

    private final GstSummaryService summaryService;

    @GetMapping("/api/pumps/{pumpId}/reports/gst-summary")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'ACCOUNTANT')")
    public GstSummaryResponse getGstSummary(@PathVariable Long pumpId, @RequestParam String month) {
        return summaryService.summaryOf(pumpId, month);
    }
}
