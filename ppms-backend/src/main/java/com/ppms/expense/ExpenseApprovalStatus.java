package com.ppms.expense;

public enum ExpenseApprovalStatus {
    /** Submitted by OPERATOR/MANAGER and waiting for Owner/Admin review. */
    PENDING_APPROVAL,
    /** Approved — included in P&L and balance sheets. */
    APPROVED,
    /** Rejected — excluded from P&L and balance sheets. */
    REJECTED
}
