package com.ppms.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves the caller's IP. X-Forwarded-For is trusted only when the direct peer is a
 * configured proxy (single IPs or CIDR ranges), so clients cannot spoof their IP.
 */
@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(@Value("${ppms.security.trusted-proxy-ips}") String trustedProxyIps) {
        this.trustedProxies = Arrays.stream(trustedProxyIps.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(remoteAddr));
    }
}
