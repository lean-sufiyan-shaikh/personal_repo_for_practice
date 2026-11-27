package com.ppms.credit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Takes an approved payment back off a client's account on the day it is reversed, such as for a bounced cheque.
 * The payment itself stays on the ledger, marked REVERSED. A bounce charge is a separate debit on the account.
 */
@Entity
@Table(name = "credit_payment_reversals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditPaymentReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "bounce_charge")
    private BigDecimal bounceCharge;

    @Column(name = "reversal_date", nullable = false)
    private LocalDate reversalDate;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "reversed_by_user_id", nullable = false)
    private Long reversedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
