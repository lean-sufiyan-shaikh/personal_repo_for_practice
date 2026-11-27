package com.ppms.credit;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Wrong balance-page PINs for one phone at one pump; the phone is kept only as a hash. */
@Entity
@Table(name = "credit_portal_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditPortalAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pump_id", nullable = false)
    private Long pumpId;

    @Column(name = "phone_hash", nullable = false, length = 64)
    private String phoneHash;

    /** The client with this phone when the last wrong PIN was tried; null for a phone that is no client's. */
    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    /** Lockouts since the last right PIN; each one lasts twice as long as the one before. */
    @Column(name = "lock_count", nullable = false)
    private int lockCount;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_failed_at")
    private OffsetDateTime lastFailedAt;
}
