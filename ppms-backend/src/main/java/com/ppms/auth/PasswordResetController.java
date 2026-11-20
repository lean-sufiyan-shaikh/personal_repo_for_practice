package com.ppms.auth;

import com.ppms.common.exception.BusinessException;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the forgot-password / reset-password flow.
 *
 * These endpoints are public (/api/auth/**) — no authentication token required.
 * forgot-password is rate-limited per phone number and per IP (LoginRateLimiterService),
 * with nginx limit_req on /api/auth/ as an outer layer.
 *
 * Security notes:
 *  - We return the same generic response whether the phone exists or not,
 *    to prevent account enumeration attacks.
 *  - Tokens expire after 1 hour.
 *  - Used tokens are immediately marked used_at and cannot be reused.
 *  - Only the SHA-256 hash of a token is stored; the raw token exists only in the email.
 *  - A successful reset bumps the user's token version, signing out every existing session.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class PasswordResetController {

    private static final int TOKEN_EXPIRY_HOURS = 1;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private final UserRepository             userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final JavaMailSender             mailSender;
    private final PasswordEncoder            passwordEncoder;
    private final LoginRateLimiterService    rateLimiterService;
    private final ClientIpResolver           clientIpResolver;

    /**
     * POST /api/auth/forgot-password
     * Request body: { "phoneNumber": "9876543210" }
     *
     * Looks up the user by phone number. If they have an email set, sends a reset link.
     * Returns 200 OK regardless (no account enumeration).
     *
     * Note: @Transactional is intentionally absent here.
     * The token is saved via Spring Data's own transactional save (commits immediately),
     * and the SMTP call happens AFTER the DB commit.
     * This prevents the "email sent but DB rolled back" inconsistency that would occur
     * if the SMTP call were inside a @Transactional boundary.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {

        rateLimiterService.checkPasswordResetAllowed(request.getPhoneNumber(), clientIpResolver.resolve(httpRequest));

        userRepository.findByPhoneNumber(request.getPhoneNumber()).ifPresent(user -> {
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                // No email on record — silently skip. Logging at WARN so admin can investigate.
                log.warn("Password reset requested for phone={} but no email is configured for userId={}",
                        PhoneNumberMasking.mask(request.getPhoneNumber()), user.getId());
                return;
            }

            UUID rawToken = UUID.randomUUID();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .userId(user.getId())
                    .tokenHash(TokenHashing.sha256Hex(rawToken.toString()))
                    .expiresAt(OffsetDateTime.now().plusHours(TOKEN_EXPIRY_HOURS))
                    .build();
            // Spring Data's save() is @Transactional internally — the token is committed
            // to DB before sendResetEmail() is called below.
            tokenRepository.save(resetToken);
            log.info("Password reset token created for userId={}, tokenId={}", user.getId(), resetToken.getId());

            // Email is sent AFTER the DB commit — if mail fails, the token is still valid
            // and the user can re-request. This avoids the inverse problem of sending an
            // email for a token that was never persisted.
            sendResetEmail(user, rawToken);
        });

        // Generic response — do not reveal whether the phone number exists or has an email
        return ResponseEntity.ok(Map.of("message",
                "If this phone number is registered and has an email on file, a reset link has been sent."));
    }

    /**
     * POST /api/auth/reset-password
     * Request body: { "token": "uuid", "newPassword": "..." }
     *
     * Validates the token, updates the password, and marks the token as used.
     */
    @PostMapping("/reset-password")
    @Transactional
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {

        UUID tokenUuid;
        try {
            tokenUuid = UUID.fromString(request.getToken());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid reset token format.");
        }

        PasswordResetToken resetToken = tokenRepository.findByTokenHashForUpdate(TokenHashing.sha256Hex(tokenUuid.toString()))
                .orElseThrow(() -> new BusinessException("Invalid or expired password reset link."));

        if (resetToken.getUsedAt() != null) {
            throw new BusinessException("This password reset link has already been used. Please request a new one.");
        }

        if (OffsetDateTime.now().isAfter(resetToken.getExpiresAt())) {
            throw new BusinessException("This password reset link has expired. Please request a new one.");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException("User not found for this reset token."));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);

        resetToken.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(resetToken);

        log.info("Password reset completed for userId={}, tokenId={}", user.getId(), resetToken.getId());

        return ResponseEntity.ok(Map.of("message", "Password has been reset successfully. You can now log in."));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void sendResetEmail(User user, UUID token) {
        try {
            String resetUrl = frontendUrl + "/reset-password?token=" + token;

            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(user.getEmail());
            mail.setSubject("PPMS — Password Reset Request");
            mail.setText(
                    "Hi " + user.getFullName() + ",\n\n" +
                    "You requested a password reset for your PPMS account.\n\n" +
                    "Click the link below to reset your password (valid for " + TOKEN_EXPIRY_HOURS + " hour):\n" +
                    resetUrl + "\n\n" +
                    "If you did not request this, you can safely ignore this email.\n\n" +
                    "— PPMS Team"
            );
            mailSender.send(mail);
        } catch (Exception e) {
            // Do not expose mail failure to the caller — log it for ops visibility
            log.error("Failed to send password reset email to userId={}", user.getId(), e);
        }
    }

    // ── Request DTOs ─────────────────────────────────────────────────────────

    @Data
    public static class ForgotPasswordRequest {
        @NotBlank(message = "Phone number is required")
        private String phoneNumber;
    }

    @Data
    public static class ResetPasswordRequest {
        @NotBlank(message = "Token is required")
        private String token;

        @NotBlank(message = "New password is required")
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        private String newPassword;
    }
}
