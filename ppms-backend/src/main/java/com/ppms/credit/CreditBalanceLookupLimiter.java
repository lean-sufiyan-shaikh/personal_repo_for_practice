package com.ppms.credit;

import java.util.Map;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits the unauthenticated credit-balance lookup per IP address. Wrong PINs for a phone are counted and locked
 * out in the database by {@link CreditPortalAttemptService}.
 */
@Slf4j
@Service
public class CreditBalanceLookupLimiter {

    static final int MAX_LOOKUPS_PER_IP = 20;
    static final int WINDOW_MINUTES = 15;

    private final Cache<String, AtomicInteger> lookupCounts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(WINDOW_MINUTES))
            .maximumSize(100_000)
            .build();

    public void checkLookupAllowed(String ipAddress) {
        int ipCount = lookupCounts.get(ipAddress, key -> new AtomicInteger()).incrementAndGet();
        if (ipCount > MAX_LOOKUPS_PER_IP) {
            log.warn("Credit balance lookup blocked: ip={} lookups={} in last {}m", ipAddress, ipCount, WINDOW_MINUTES);
            throw new TooManyRequestsException(ErrorCodes.TOO_MANY_BALANCE_LOOKUPS, Map.of("minutes", WINDOW_MINUTES),
                    "Too many balance lookups. Please wait " + WINDOW_MINUTES + " minutes before trying again.");
        }
    }
}
