package com.ppms.cash;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Cash taken from the safe to the bank; its BANK_DEPOSIT cash event takes the amount out of the safe's ledger. */
@Entity
@Table(name = "bank_deposits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /** Free text such as "SBI current ••1234": the app keeps no list of bank accounts. */
    @Column(name = "bank_account", nullable = false, length = 100)
    private String bankAccount;

    @Column(name = "deposit_date", nullable = false)
    private LocalDate depositDate;

    @Column(name = "slip_reference", nullable = false, length = 100)
    private String slipReference;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "cash_event_id", nullable = false)
    private Long cashEventId;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
