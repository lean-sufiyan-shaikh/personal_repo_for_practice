package com.ppms.credit;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class CreditClientResponse {
    private Long id;
    private Long pumpId;
    private String name;
    private String phone;
    private String notes;
    private BigDecimal creditLimit;
    // When true the account has no credit limit; otherwise creditLimit applies, and 0 allows no credit
    private Boolean unlimited;
    // Total credit sales + interest charges minus total payments — computed on the fly
    private BigDecimal outstandingBalance;
    // Unpaid portion of applied interest charges (interest-first allocation). Subset of outstandingBalance.
    private BigDecimal outstandingInterest;
    // Total interest that has been covered by payments historically
    private BigDecimal totalInterestRecovered;
    // Simple interest rate charged per month (0 = no interest)
    private BigDecimal monthlyInterestRate;
    // Days after first credit entry before interest begins accruing
    private Integer interestGraceDays;
    private OffsetDateTime createdAt;

    // ── Sub-account hierarchy ─────────────────────────────────────────────────
    /** Null for root/parent accounts. Non-null when this client is a sub-account. */
    private Long parentClientId;
    /** Human-readable name of the parent account. Null for root accounts. */
    private String parentClientName;
    /** True if this root account has one or more sub-accounts under it. */
    private Boolean isParent;
    /** False when the client has been soft-disabled by an Owner/Admin. */
    private Boolean isActive;
    private Boolean hasPortalPin;
    // The PIN is shorter than six digits or easy to guess; the owner should set a new one
    private Boolean weakPortalPin;
    private String gstin;
    private String billingAddress;
    // What the account has paid ahead of its bills (a main account only); new bills use it up first
    private BigDecimal advance;
    // Security deposit held; never part of the outstanding balance until an owner applies it
    private BigDecimal depositHeld;
    private OffsetDateTime closedAt;
    private String closeReason;
    private Long mergedIntoClientId;
    // First day of a bad-debt write-off; null when never written off
    private LocalDate writtenOffOn;
}
