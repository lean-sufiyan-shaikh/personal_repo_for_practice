package com.ppms.expense;

public enum ExpenseApprovalStatus {
    /** Saved but not yet submitted for approval. Only DRAFT expenses can be deleted. */
    DRAFT,
    /** Submitted by OPERATOR/MANAGER and waiting for Owner/Admin review. */
    PENDING_APPROVAL,
    /** Approved — included in P&L and balance sheets. */
    APPROVED,
    /** Rejected — excluded from P&L and balance sheets. */
    REJECTED,
    /** Credit payments only: approved, then reversed by a reversal dated the day it was reversed. */
    REVERSED,
    /** Expenses only: approved, then voided by an Owner or Admin; its cash movement is reversed and it counts nowhere. */
    VOIDED
}
