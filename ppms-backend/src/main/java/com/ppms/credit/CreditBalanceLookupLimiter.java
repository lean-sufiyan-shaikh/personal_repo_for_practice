package com.ppms.credit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ppms.common.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits the unauthenticated credit-balance lookup per IP so phone numbers cannot be
 * enumerated to harvest client names and balances.
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
        int count = lookupCounts.get(ipAddress, key -> new AtomicInteger()).incrementAndGet();
        if (count > MAX_LOOKUPS_PER_IP) {
            log.warn("Credit balance lookup blocked: ip={} lookups={} in last {}m", ipAddress, count, WINDOW_MINUTES);
            throw new TooManyRequestsException(
                    "Too many balance lookups. Please wait " + WINDOW_MINUTES + " minutes before trying again.");
        }
    }
}
