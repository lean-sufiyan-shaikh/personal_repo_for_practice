package com.ppms.cash;

import com.ppms.audit.AuditAction;

/** When a safe count is recorded compared with the business day it is for. */
enum SafeCountTiming {
    ON_THE_DAY(AuditAction.SAFE_COUNTED),
    /** For yesterday, before the pump's morning cut-off. */
    NEXT_MORNING(AuditAction.SAFE_COUNTED_LATE),
    /** For an earlier day after the cut-off: owner or admin only, with a reason. */
    BACK_DATED(AuditAction.SAFE_COUNT_BACK_DATED);

    private final AuditAction auditAction;

    SafeCountTiming(AuditAction auditAction) {
        this.auditAction = auditAction;
    }

    AuditAction auditAction() {
        return auditAction;
    }
}
