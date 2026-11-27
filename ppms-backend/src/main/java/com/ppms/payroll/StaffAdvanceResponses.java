package com.ppms.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Responses of staff cash advances. */
public final class StaffAdvanceResponses {

    private StaffAdvanceResponses() {
    }

    /** recovered is what payroll has taken back so far; outstanding is zero for a voided advance. */
    public record AdvanceResponse(Long id, Long staffUserId, String staffName, BigDecimal amount, LocalDate advanceDate,
                                  String note, BigDecimal recovered, BigDecimal outstanding, String status,
                                  String givenByName, String voidReason, OffsetDateTime voidedAt, OffsetDateTime createdAt) {}

    public record StaffOutstanding(Long staffUserId, String staffName, BigDecimal outstanding) {}

    /** Advances newest first, and what each staff member still owes, largest first. */
    public record AdvanceListResponse(List<AdvanceResponse> advances, List<StaffOutstanding> outstandingByStaff) {}
}
