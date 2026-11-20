package com.ppms.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ppms.common.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brute-force protection for the login and forgot-password endpoints.
 *
 * Login: sliding 15-minute window over the login_attempts table, per phone number
 * (stops guessing one account) and per IP (stops spraying one password across many accounts).
 *
 * Forgot-password: fixed 15-minute in-memory window per IP and per phone number
 * (stops flooding a user's inbox with reset emails).
 */
@Slf4j
@Service
public class LoginRateLimiterService {

    static final int MAX_FAILURES_PER_PHONE = 5;
    static final int MAX_FAILURES_PER_IP = 20;
    static final int MAX_RESET_REQUESTS_PER_PHONE = 3;
    static final int MAX_RESET_REQUESTS_PER_IP = 10;
    static final int WINDOW_MINUTES = 15;

    private final LoginAttemptRepository attemptRepository;
    private final Cache<String, AtomicInteger> resetRequestCounts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(WINDOW_MINUTES))
            .maximumSize(100_000)
            .build();

    public LoginRateLimiterService(LoginAttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    /**
     * Called before the password is verified so blocked callers do not burn bcrypt CPU.
     */
    public void checkLoginAllowed(String phoneNumber, String ipAddress) {
        OffsetDateTime windowStart = OffsetDateTime.now().minusMinutes(WINDOW_MINUTES);

        long phoneFailures = attemptRepository.countRecentFailures(phoneNumber, windowStart);
        if (phoneFailures >= MAX_FAILURES_PER_PHONE) {
            log.warn("Login blocked by phone limit: phone={} failures={} in last {}m",
                    PhoneNumberMasking.mask(phoneNumber), phoneFailures, WINDOW_MINUTES);
            throw tooManyRequests();
        }

        if (ipAddress != null) {
            long ipFailures = attemptRepository.countRecentFailuresByIp(ipAddress, windowStart);
            if (ipFailures >= MAX_FAILURES_PER_IP) {
                log.warn("Login blocked by IP limit: ip={} failures={} in last {}m",
                        ipAddress, ipFailures, WINDOW_MINUTES);
                throw tooManyRequests();
            }
        }
    }

    public void checkPasswordResetAllowed(String phoneNumber, String ipAddress) {
        boolean phoneOverLimit = incrementAndCheck("phone:" + phoneNumber, MAX_RESET_REQUESTS_PER_PHONE);
        boolean ipOverLimit = ipAddress != null
                && incrementAndCheck("ip:" + ipAddress, MAX_RESET_REQUESTS_PER_IP);
        if (phoneOverLimit || ipOverLimit) {
            log.warn("Password reset request blocked: phone={} ip={}",
                    PhoneNumberMasking.mask(phoneNumber), ipAddress);
            throw new TooManyRequestsException(
                    "Too many password reset requests. Please wait " + WINDOW_MINUTES + " minutes before trying again.");
        }
    }

    @Transactional
    public void recordAttempt(String phoneNumber, String ipAddress, boolean success) {
        attemptRepository.save(LoginAttempt.builder()
                .phoneNumber(phoneNumber)
                .ipAddress(ipAddress)
                .success(success)
                .build());
    }

    private boolean incrementAndCheck(String key, int limit) {
        return resetRequestCounts.get(key, k -> new AtomicInteger()).incrementAndGet() > limit;
    }

    private TooManyRequestsException tooManyRequests() {
        return new TooManyRequestsException(
                "Too many failed login attempts. Please wait " + WINDOW_MINUTES + " minutes before trying again.");
    }
}
