package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.TooManyRequestsException;
import com.ppms.common.time.BusinessClock;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wrong balance-page PINs per pump and phone, kept in the database. Five wrong PINs lock the phone for 15 minutes,
 * and each further lockout doubles, up to a day. A phone that is no client's is tracked and locked the same way, so
 * a lockout tells nothing about which phones are clients. The owner is notified of every lockout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditPortalAttemptService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration FIRST_LOCK = Duration.ofMinutes(15);
    static final Duration LONGEST_LOCK = Duration.ofHours(24);

    private final CreditPortalAttemptRepository attemptRepository;
    private final CreditClientRepository clientRepository;
    private final NotificationDedupService notificationDedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public void requireNotLocked(Long pumpId, String phone) {
        OffsetDateTime now = businessClock.now();
        attemptRepository.findByPumpIdAndPhoneHash(pumpId, phoneHash(pumpId, phone))
                .filter(attempt -> attempt.getLockedUntil() != null && attempt.getLockedUntil().isAfter(now))
                .ifPresent(attempt -> {
                    long minutes = Math.max(1, (Duration.between(now, attempt.getLockedUntil()).toSeconds() + 59) / 60);
                    throw new TooManyRequestsException(ErrorCodes.TOO_MANY_BALANCE_LOOKUPS, Map.of("minutes", minutes),
                            "Too many balance lookups. Please wait " + minutes + " minutes before trying again.");
                });
    }

    /** Counts a wrong phone or PIN; the fifth in a row locks the phone. */
    @Transactional
    public void recordFailure(Long pumpId, String phone, Long clientId) {
        String hash = phoneHash(pumpId, phone);
        attemptRepository.insertIfMissing(pumpId, hash);
        CreditPortalAttempt attempt = attemptRepository.findForUpdate(pumpId, hash)
                .orElseThrow(() -> new IllegalStateException("Balance-page attempt row missing for pump " + pumpId));
        OffsetDateTime now = businessClock.now();
        attempt.setClientId(clientId);
        attempt.setLastFailedAt(now);
        attempt.setFailedAttempts(attempt.getFailedAttempts() + 1);
        if (attempt.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
            attempt.setLockCount(attempt.getLockCount() + 1);
            attempt.setFailedAttempts(0);
            Duration lock = lockDuration(attempt.getLockCount());
            attempt.setLockedUntil(now.plus(lock));
            reportLockout(pumpId, attempt, lock);
        }
        attemptRepository.save(attempt);
    }

    @Transactional
    public void clearAttempts(Long pumpId, String phone) {
        attemptRepository.deleteByPumpIdAndPhoneHash(pumpId, phoneHash(pumpId, phone));
    }

    /** 15 minutes for the first lockout, doubling with each one after it, never more than a day. */
    static Duration lockDuration(int lockCount) {
        int doublings = Math.min(lockCount - 1, 10);
        Duration lock = FIRST_LOCK.multipliedBy(1L << doublings);
        return lock.compareTo(LONGEST_LOCK) > 0 ? LONGEST_LOCK : lock;
    }

    private void reportLockout(Long pumpId, CreditPortalAttempt attempt, Duration lock) {
        long minutes = lock.toMinutes();
        String clientName = attempt.getClientId() == null ? null
                : clientRepository.findById(attempt.getClientId()).map(CreditClient::getName).orElse(null);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("minutes", minutes);
        String dedupKey;
        String message;
        if (clientName != null) {
            params.put("clientName", clientName);
            params.put("clientId", attempt.getClientId());
            dedupKey = "CREDIT_PORTAL_LOCKED:client:" + attempt.getClientId() + ":" + attempt.getLockedUntil().toInstant();
            message = "The balance page of " + clientName + " was locked for " + minutes
                    + " minutes after " + MAX_FAILED_ATTEMPTS + " wrong PINs.";
        } else {
            dedupKey = "CREDIT_PORTAL_LOCKED:phone:" + attempt.getPhoneHash().substring(0, 16) + ":"
                    + attempt.getLockedUntil().toInstant();
            message = "The balance page was locked for " + minutes + " minutes after " + MAX_FAILED_ATTEMPTS
                    + " wrong tries with a number that is not a client's.";
        }
        notificationDedupService.maybeInsert(pumpId, NotificationType.CREDIT_PORTAL_LOCKED, dedupKey,
                "Balance Page Locked", message, params);
        auditService.log(pumpId, AuditAction.CREDIT_PORTAL_LOCKED, "CreditClient",
                attempt.getClientId() == null ? null : attempt.getClientId().toString(),
                message + " Lockout number " + attempt.getLockCount() + ".", null, "Balance page");
        log.warn("Credit balance page locked: pump={} client={} lockout={} minutes={}",
                pumpId, attempt.getClientId(), attempt.getLockCount(), minutes);
    }

    /** The phone is kept only as a hash, so this table never holds a readable number. */
    static String phoneHash(Long pumpId, String phone) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((pumpId + ":" + phone).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
