package com.ppms.credit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ppms.auth.TokenHashing;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The short session a client opens on the portal by proving their phone and PIN. Its token lets them read their
 * recent transactions and download a statement without sending the PIN again. Only a hash of the token is kept, in
 * memory, for a few minutes; statement downloads are limited per session.
 */
@Slf4j
@Service
public class CreditPortalSessionService {

    static final int SESSION_MINUTES = 15;
    static final int MAX_STATEMENT_DOWNLOADS = 5;
    private static final int TOKEN_BYTES = 32;

    public record PortalSession(Long pumpId, Long clientId) {}

    private final SecureRandom random = new SecureRandom();
    private final Cache<String, PortalSession> sessionsByTokenHash = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(SESSION_MINUTES))
            .maximumSize(100_000)
            .build();
    private final Cache<String, AtomicInteger> downloadsByTokenHash = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(SESSION_MINUTES))
            .maximumSize(100_000)
            .build();

    public String open(Long pumpId, Long clientId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessionsByTokenHash.put(TokenHashing.sha256Hex(token), new PortalSession(pumpId, clientId));
        return token;
    }

    public PortalSession require(String token) {
        PortalSession session = token == null || token.isBlank() ? null
                : sessionsByTokenHash.getIfPresent(TokenHashing.sha256Hex(token));
        if (session == null) {
            throw new BusinessException(ErrorCodes.PORTAL_SESSION_EXPIRED, Map.of("minutes", SESSION_MINUTES),
                    "Your session has ended. Check your balance again with your phone and PIN.");
        }
        return session;
    }

    public void checkStatementDownloadAllowed(String token) {
        int downloads = downloadsByTokenHash.get(TokenHashing.sha256Hex(token), key -> new AtomicInteger()).incrementAndGet();
        if (downloads > MAX_STATEMENT_DOWNLOADS) {
            log.warn("Portal statement downloads blocked for a session: downloads={}", downloads);
            throw new TooManyRequestsException(ErrorCodes.TOO_MANY_STATEMENT_DOWNLOADS,
                    Map.of("count", MAX_STATEMENT_DOWNLOADS, "minutes", SESSION_MINUTES),
                    "At most " + MAX_STATEMENT_DOWNLOADS + " statements can be downloaded in " + SESSION_MINUTES
                            + " minutes.");
        }
    }
}
