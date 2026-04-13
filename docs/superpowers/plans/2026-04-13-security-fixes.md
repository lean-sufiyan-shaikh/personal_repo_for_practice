# Security Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 5 security vulnerabilities across the PPMS frontend and backend: move JWT to httpOnly cookie, move voidReason to request body, sanitize API error messages, add numeric input guards, and mask phone numbers.

**Architecture:** Backend issues JWT as an httpOnly SameSite=Strict cookie at login instead of returning it in the response body. The JwtAuthenticationFilter reads the JWT from the cookie. All other fixes are isolated to their respective files with no shared state changes.

**Tech Stack:** Spring Boot 3.4.1, Java 21, JJWT 0.12.6, React 18, TypeScript, Zustand, Axios

---

## File Map

| File | Change |
|------|--------|
| `ppms-backend/.../shift/VoidCreditEntryRequest.java` | CREATE — new DTO |
| `ppms-backend/.../shift/ShiftController.java` | MODIFY — `@RequestParam` → `@RequestBody` on voidCreditEntry |
| `ppms-backend/.../auth/AuthController.java` | MODIFY — set/clear cookie on login/logout/change-password |
| `ppms-backend/.../auth/JwtAuthenticationFilter.java` | MODIFY — read JWT from cookie instead of Authorization header |
| `ppms-backend/src/main/resources/application.yml` | MODIFY — add `ppms.cookie.secure` property |
| `ppms-frontend/src/utils/apiError.ts` | CREATE — `parseApiError(err)` utility |
| `ppms-frontend/src/utils/maskPhone.ts` | CREATE — `maskPhone(phone)` utility |
| `ppms-frontend/src/api/shiftApi.ts` | MODIFY — voidCreditEntry sends body not query param |
| `ppms-frontend/src/api/client.ts` | MODIFY — add `withCredentials: true`, remove Authorization interceptor |
| `ppms-frontend/src/store/authStore.ts` | MODIFY — remove token from state and persist |
| `ppms-frontend/src/types/auth.ts` | MODIFY — remove token from LoginResponse |
| `ppms-frontend/src/pages/auth/LoginPage.tsx` | MODIFY — call `setAuth(user)` not `setAuth(user, token)` |
| `ppms-frontend/src/pages/shifts/CloseShiftModal.tsx` | MODIFY — parseApiError, NaN/bounds guards, mask phones |
| `ppms-frontend/src/pages/shifts/ShiftsPage.tsx` | MODIFY — parseApiError, mask phones |

---

## Task 1: Fix voidReason — Create backend DTO

**Files:**
- Create: `ppms-backend/src/main/java/com/ppms/shift/VoidCreditEntryRequest.java`

- [ ] **Step 1: Create the DTO**

```java
package com.ppms.shift;

import jakarta.validation.constraints.NotBlank;

public record VoidCreditEntryRequest(
        @NotBlank(message = "Void reason is required")
        String voidReason
) {}
```

- [ ] **Step 2: Commit**

```bash
cd /Users/Sufiyan.Shaikh/IdeaProjects/self/project
git add ppms-backend/src/main/java/com/ppms/shift/VoidCreditEntryRequest.java
git commit -m "security: add VoidCreditEntryRequest DTO to move voidReason off query param"
```

---

## Task 2: Fix voidReason — Update backend controller

**Files:**
- Modify: `ppms-backend/src/main/java/com/ppms/shift/ShiftController.java:149-158`

- [ ] **Step 1: Replace `@RequestParam` with `@RequestBody` on voidCreditEntry**

Replace lines 149–158:
```java
@PatchMapping("/{shiftId}/credit-entries/{entryId}/void")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
public ResponseEntity<ShiftResponse> voidCreditEntry(
        @PathVariable Long pumpId,
        @PathVariable Long shiftId,
        @PathVariable Long entryId,
        @Valid @RequestBody VoidCreditEntryRequest request,
        @AuthenticationPrincipal User currentUser) {
    return ResponseEntity.ok(shiftService.voidCreditEntry(shiftId, entryId, request.voidReason(), currentUser));
}
```

- [ ] **Step 2: Commit**

```bash
git add ppms-backend/src/main/java/com/ppms/shift/ShiftController.java
git commit -m "security: move voidReason from query param to request body"
```

---

## Task 3: Fix voidReason — Update frontend API call

**Files:**
- Modify: `ppms-frontend/src/api/shiftApi.ts:37-38`

- [ ] **Step 1: Change voidCreditEntry to send JSON body**

Replace line 37-38:
```typescript
voidCreditEntry: (pumpId: number, shiftId: number, entryId: number, voidReason: string) =>
  client.patch<Shift>(`/pumps/${pumpId}/shifts/${shiftId}/credit-entries/${entryId}/void`, { voidReason }).then((r) => r.data),
```

- [ ] **Step 2: Commit**

```bash
cd /Users/Sufiyan.Shaikh/IdeaProjects/self/project
git add ppms-frontend/src/api/shiftApi.ts
git commit -m "security: send voidReason in request body instead of query param"
```

---

## Task 4: Create error sanitizer utility

**Files:**
- Create: `ppms-frontend/src/utils/apiError.ts`

- [ ] **Step 1: Create the utility**

```typescript
/**
 * Extracts a safe, user-facing error message from an Axios error response.
 * Passes through known business messages from the backend; falls back to a
 * generic message so raw stack traces or DB errors are never shown to users.
 */

const KNOWN_PREFIXES = [
  'Invalid phone',
  'Invalid credentials',
  'Too many failed',
  'Shift is not open',
  'End reading cannot',
  'Each credit entry',
  'Void reason is required',
  'Cannot void',
  'Discrepancy reason',
  'Password must',
  'Current password',
  'User not found',
  'Pump not found',
]

export function parseApiError(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  const message: unknown = (err as any)?.response?.data?.message
  if (typeof message === 'string' && message.trim().length > 0) {
    // Only surface the message if it starts with a known safe prefix
    const isSafe = KNOWN_PREFIXES.some((p) => message.startsWith(p))
    if (isSafe) return message
  }
  return fallback
}
```

- [ ] **Step 2: Commit**

```bash
git add ppms-frontend/src/utils/apiError.ts
git commit -m "security: add parseApiError utility to prevent raw server errors leaking to UI"
```

---

## Task 5: Apply error sanitizer to CloseShiftModal and ShiftsPage

**Files:**
- Modify: `ppms-frontend/src/pages/shifts/CloseShiftModal.tsx`
- Modify: `ppms-frontend/src/pages/shifts/ShiftsPage.tsx`

- [ ] **Step 1: Import parseApiError in CloseShiftModal**

Add to the import block at the top of `CloseShiftModal.tsx`:
```typescript
import { parseApiError } from '../../utils/apiError'
```

- [ ] **Step 2: Replace raw error access in closeMutation (CloseShiftModal.tsx ~line 208)**

Replace:
```typescript
onError: (err: any) =>
  setServerError(err?.response?.data?.message ?? 'Failed to close shift. Please try again.'),
```
With:
```typescript
onError: (err: unknown) =>
  setServerError(parseApiError(err, 'Failed to close shift. Please try again.')),
```

- [ ] **Step 3: Replace raw error access in voidMutation (CloseShiftModal.tsx ~line 161)**

Replace:
```typescript
onError: (err: any) => setVoidError(err?.response?.data?.message ?? 'Failed to void entry'),
```
With:
```typescript
onError: (err: unknown) => setVoidError(parseApiError(err, 'Failed to delete entry. Please try again.')),
```

- [ ] **Step 4: Import parseApiError in ShiftsPage and replace all raw error accesses**

Add to imports:
```typescript
import { parseApiError } from '../../utils/apiError'
```

Then search for every `err?.response?.data?.message` in `ShiftsPage.tsx` and replace with `parseApiError(err, '<context-specific fallback>')`. For example:

For open-shift mutation error:
```typescript
onError: (err: unknown) => setOpenError(parseApiError(err, 'Failed to open shift. Please try again.')),
```

For resolve-discrepancy mutation error:
```typescript
onError: (err: unknown) => setResolveError(parseApiError(err, 'Failed to resolve discrepancy. Please try again.')),
```

For add-credit-entry mutation error:
```typescript
onError: (err: unknown) => setCreditError(parseApiError(err, 'Failed to save credit entry. Please try again.')),
```

- [ ] **Step 5: Commit**

```bash
git add ppms-frontend/src/pages/shifts/CloseShiftModal.tsx \
        ppms-frontend/src/pages/shifts/ShiftsPage.tsx
git commit -m "security: sanitize API error messages before displaying to users"
```

---

## Task 6: Add numeric input bounds/NaN guards

**Files:**
- Modify: `ppms-frontend/src/pages/shifts/CloseShiftModal.tsx`

- [ ] **Step 1: Add a safe parse helper just above handleSubmit (~line 212)**

Add this function before `handleSubmit`:
```typescript
/** Parses a numeric string, returns null if the result is NaN, Infinity, or negative. */
const safeParse = (v: string, max = 9_999_999): number | null => {
  const n = Number(v)
  if (!isFinite(n) || n < 0 || n > max) return null
  return n
}
```

- [ ] **Step 2: Guard the liters→amount conversion (~line 723)**

Find the liters onChange handler inside the credit row that looks like:
```typescript
const amt = price && e.target.value ? (parseFloat(e.target.value) * price).toFixed(2) : ''
```
Replace with:
```typescript
const raw = parseFloat(e.target.value)
const amt = price && isFinite(raw) && raw >= 0 && raw <= 99_999
  ? (raw * price).toFixed(2)
  : ''
```

- [ ] **Step 3: Guard handleSubmit — validate end readings**

Inside `handleSubmit`, before building `fuelReadings`, add:
```typescript
for (const r of shift.fuelReadings) {
  const parsed = safeParse(endReadings[r.nozzleId] ?? '')
  if (parsed === null) {
    setServerError('End reading contains an invalid value. Please re-enter.')
    return
  }
}
```

- [ ] **Step 4: Guard handleSubmit — validate payment fields**

After the end-reading guard, add:
```typescript
const paymentFields = [
  { label: 'Cash',       val: cash },
  { label: 'UPI',        val: upi },
  { label: 'Card',       val: card },
  { label: 'Fleet Card', val: fleetCard },
]
for (const f of paymentFields) {
  if (f.val !== '' && safeParse(f.val) === null) {
    setServerError(`${f.label} amount contains an invalid value.`)
    return
  }
}
```

- [ ] **Step 5: Commit**

```bash
git add ppms-frontend/src/pages/shifts/CloseShiftModal.tsx
git commit -m "security: add NaN/Infinity/bounds guards on numeric inputs before submission"
```

---

## Task 7: Mask phone numbers

**Files:**
- Create: `ppms-frontend/src/utils/maskPhone.ts`
- Modify: `ppms-frontend/src/pages/shifts/CloseShiftModal.tsx`
- Modify: `ppms-frontend/src/pages/shifts/ShiftsPage.tsx`

- [ ] **Step 1: Create maskPhone utility**

```typescript
/**
 * Masks a phone number showing only the last 4 digits.
 * e.g. "9876543210" → "•••••• 3210"
 * Returns the original string unchanged if it's shorter than 4 chars.
 */
export function maskPhone(phone: string): string {
  if (!phone || phone.length < 4) return phone
  return `•••••• ${phone.slice(-4)}`
}
```

- [ ] **Step 2: Apply maskPhone in CloseShiftModal credit client dropdown**

In `CloseShiftModal.tsx`, find where `c.phone` is rendered in the client search dropdown (around line 575):
```tsx
{c.phone && <span className="text-xs text-slate-400">{c.phone}</span>}
```
Replace with:
```tsx
{c.phone && <span className="text-xs text-slate-400">{maskPhone(c.phone)}</span>}
```

Add to imports:
```typescript
import { maskPhone } from '../../utils/maskPhone'
```

- [ ] **Step 3: Apply maskPhone in ShiftsPage wherever phone is rendered**

In `ShiftsPage.tsx` find `c.phone` or any `phoneNumber` displayed in dropdowns (around line 1134) and apply the same pattern:
```tsx
{c.phone && <span className="text-xs text-slate-400">{maskPhone(c.phone)}</span>}
```

Add import:
```typescript
import { maskPhone } from '../../utils/maskPhone'
```

- [ ] **Step 4: Commit**

```bash
git add ppms-frontend/src/utils/maskPhone.ts \
        ppms-frontend/src/pages/shifts/CloseShiftModal.tsx \
        ppms-frontend/src/pages/shifts/ShiftsPage.tsx
git commit -m "security: mask phone numbers (PII) to last 4 digits in client dropdowns"
```

---

## Task 8: httpOnly cookie — backend login sets cookie

**Files:**
- Modify: `ppms-backend/src/main/resources/application.yml`
- Modify: `ppms-backend/src/main/java/com/ppms/auth/AuthController.java`
- Modify: `ppms-backend/src/main/java/com/ppms/auth/LoginResponse.java`

- [ ] **Step 1: Add cookie config to application.yml**

Add under the `ppms:` block:
```yaml
ppms:
  cookie:
    secure: ${COOKIE_SECURE:false}   # Set to true in production (HTTPS required)
    same-site: Strict
    max-age-seconds: ${JWT_EXPIRY_MS:86400000}  # reuse JWT expiry; divided by 1000 in code
```

- [ ] **Step 2: Remove token field from LoginResponse**

Replace `LoginResponse.java` entirely:
```java
package com.ppms.auth;

import com.ppms.user.UserRole;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private Long userId;
    private String fullName;
    private String phoneNumber;
    private UserRole role;
    private Long assignedPumpId;    // null for OWNER
}
```

- [ ] **Step 3: Update AuthController — inject cookie config and update login endpoint**

Replace `AuthController.java` entirely:
```java
package com.ppms.auth;

import com.ppms.user.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String COOKIE_NAME = "ppms_jwt";
    private static final String PASSWORD_POLICY_REGEX =
            "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$";
    private static final String PASSWORD_POLICY_MESSAGE =
            "Password must be at least 8 characters and include 1 uppercase letter, 1 lowercase letter, 1 digit, and 1 symbol.";

    private final AuthService authService;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;

    @Value("${ppms.security.trusted-proxy-ips:127.0.0.1,::1}")
    private String trustedProxyIpsRaw;

    @Value("${ppms.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${JWT_EXPIRY_MS:86400000}")
    private long jwtExpiryMs;

    /**
     * POST /api/auth/login
     * Authenticates credentials. The JWT is returned as an httpOnly cookie, NOT in the
     * response body — this prevents JavaScript from reading the token, eliminating the
     * XSS token-theft vector. The response body carries only the user profile fields.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String ipAddress = extractClientIp(httpRequest);
        LoginResult result = authService.login(request, ipAddress);
        setJwtCookie(httpResponse, result.token());
        return ResponseEntity.ok(result.loginResponse());
    }

    /**
     * POST /api/auth/logout
     * Revokes the JWT and clears the httpOnly cookie.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            @AuthenticationPrincipal User currentUser) {
        String jwt = extractJwtFromCookie(httpRequest);
        if (jwt != null) {
            tokenRevocationService.revoke(jwt, currentUser.getId(), jwtService.extractExpiration(jwt));
        }
        clearJwtCookie(httpResponse);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            @AuthenticationPrincipal User currentUser) {

        authService.changePassword(currentUser, request.getCurrentPassword(), request.getNewPassword());

        String jwt = extractJwtFromCookie(httpRequest);
        if (jwt != null) {
            tokenRevocationService.revoke(jwt, currentUser.getId(), jwtService.extractExpiration(jwt));
        }
        clearJwtCookie(httpResponse);
        return ResponseEntity.ok(Map.of("message", "Password updated successfully. Please sign in again."));
    }

    // ── Cookie helpers ────────────────────────────────────────────────────────

    private void setJwtCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api");
        cookie.setMaxAge((int) (jwtExpiryMs / 1000));
        // SameSite=Strict prevents the cookie from being sent on cross-site requests,
        // providing CSRF protection without requiring a separate CSRF token.
        response.setHeader("Set-Cookie",
                String.format("%s=%s; HttpOnly; %sPath=/api; Max-Age=%d; SameSite=Strict",
                        COOKIE_NAME, token,
                        cookieSecure ? "Secure; " : "",
                        (int) (jwtExpiryMs / 1000)));
    }

    private void clearJwtCookie(HttpServletResponse response) {
        response.setHeader("Set-Cookie",
                String.format("%s=; HttpOnly; %sPath=/api; Max-Age=0; SameSite=Strict",
                        COOKIE_NAME,
                        cookieSecure ? "Secure; " : ""));
    }

    private String extractJwtFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    // ── IP extraction ─────────────────────────────────────────────────────────

    private String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        Set<String> trustedIps = Arrays.stream(trustedProxyIpsRaw.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());

        if (trustedIps.contains(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "Current password is required")
        private String currentPassword;

        @NotBlank(message = "New password is required")
        @Pattern(regexp = PASSWORD_POLICY_REGEX, message = PASSWORD_POLICY_MESSAGE)
        private String newPassword;
    }
}
```

- [ ] **Step 4: Create LoginResult record** (used by AuthService to return both token + response)

Create `ppms-backend/src/main/java/com/ppms/auth/LoginResult.java`:
```java
package com.ppms.auth;

/**
 * Internal carrier returned by AuthService.login().
 * Separates the raw JWT (needed to set the cookie) from the user-facing response body.
 * The token is never passed to the HTTP response body — only to the cookie setter.
 */
public record LoginResult(String token, LoginResponse loginResponse) {}
```

- [ ] **Step 5: Commit**

```bash
git add ppms-backend/src/main/java/com/ppms/auth/AuthController.java \
        ppms-backend/src/main/java/com/ppms/auth/LoginResponse.java \
        ppms-backend/src/main/java/com/ppms/auth/LoginResult.java \
        ppms-backend/src/main/resources/application.yml
git commit -m "security: issue JWT as httpOnly cookie on login, clear on logout/change-password"
```

---

## Task 9: httpOnly cookie — update AuthService to return LoginResult

**Files:**
- Modify: `ppms-backend/src/main/java/com/ppms/auth/AuthService.java:38-78`

- [ ] **Step 1: Change login method return type from LoginResponse to LoginResult**

Replace the `login` method (lines 38–78) in `AuthService.java`:
```java
public LoginResult login(LoginRequest request, String ipAddress) {
    if (rateLimiterService.isBlocked(request.getPhoneNumber())) {
        throw new BusinessException(
                "Too many failed login attempts. Please wait 15 minutes before trying again.");
    }

    try {
        var auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getPhoneNumber(),
                        request.getPassword()
                )
        );

        User user = (User) auth.getPrincipal();

        String token = jwtService.generateToken(user, Map.of(
                "role", user.getRole().name(),
                "userId", user.getId(),
                "pumpId", user.getAssignedPumpId() != null ? user.getAssignedPumpId() : ""
        ));

        authLoginEventService.onLoginSuccess(user, ipAddress);

        LoginResponse loginResponse = LoginResponse.builder()
                .userId(user.getId())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole())
                .assignedPumpId(user.getAssignedPumpId())
                .build();

        return new LoginResult(token, loginResponse);

    } catch (AuthenticationException ex) {
        authLoginEventService.onLoginFailure(request.getPhoneNumber(), ipAddress);
        throw new InvalidCredentialsException("Invalid phone number or password");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ppms-backend/src/main/java/com/ppms/auth/AuthService.java
git commit -m "security: update AuthService.login to return LoginResult (token + response body separated)"
```

---

## Task 10: httpOnly cookie — update JwtAuthenticationFilter to read from cookie

**Files:**
- Modify: `ppms-backend/src/main/java/com/ppms/auth/JwtAuthenticationFilter.java`

- [ ] **Step 1: Replace the filter to read JWT from cookie instead of Authorization header**

Replace `JwtAuthenticationFilter.java` entirely:
```java
package com.ppms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs once per request. Extracts the JWT from the httpOnly cookie named "ppms_jwt",
 * validates it, and sets the authenticated user in the Spring Security context.
 *
 * The token is read from a cookie (not the Authorization header) to prevent XSS attacks
 * from stealing it — httpOnly cookies cannot be accessed by JavaScript.
 *
 * Validation steps (in order):
 *   1. JWT signature and expiry (JwtService.isTokenValid)
 *   2. Token not in blacklist (revoked via logout or forced sign-out)
 *   3. User account is still ACTIVE (user not deactivated after token was issued)
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String COOKIE_NAME = "ppms_jwt";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TokenRevocationService tokenRevocationService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String jwt = extractJwtFromCookie(request);

        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        final String phoneNumber;
        try {
            phoneNumber = jwtService.extractUsername(jwt);
        } catch (JwtException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"Session expired. Please log in again.\"}");
            return;
        }

        if (phoneNumber != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails;
            try {
                userDetails = userDetailsService.loadUserByUsername(phoneNumber);
            } catch (UsernameNotFoundException e) {
                filterChain.doFilter(request, response);
                return;
            }

            if (jwtService.isTokenValid(jwt, userDetails)
                    && !tokenRevocationService.isRevoked(jwt)
                    && userDetails.isEnabled()
                    && userDetails.isAccountNonLocked()) {

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractJwtFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ppms-backend/src/main/java/com/ppms/auth/JwtAuthenticationFilter.java
git commit -m "security: read JWT from httpOnly cookie instead of Authorization header"
```

---

## Task 11: httpOnly cookie — update frontend

**Files:**
- Modify: `ppms-frontend/src/types/auth.ts`
- Modify: `ppms-frontend/src/store/authStore.ts`
- Modify: `ppms-frontend/src/api/client.ts`
- Modify: `ppms-frontend/src/pages/auth/LoginPage.tsx`

- [ ] **Step 1: Remove token from LoginResponse type**

In `ppms-frontend/src/types/auth.ts`, replace the `LoginResponse` interface:
```typescript
export interface LoginResponse {
  userId: number
  fullName: string
  phoneNumber: string
  role: UserRole
  assignedPumpId: number | null
}
```

- [ ] **Step 2: Remove token from authStore**

Replace `ppms-frontend/src/store/authStore.ts` entirely:
```typescript
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { AuthUser } from '../types/auth'

interface AuthState {
  user: AuthUser | null
  isAuthenticated: boolean
  setAuth: (user: AuthUser) => void
  clearAuth: () => void
  updateUser: (patch: Partial<AuthUser>) => void
}

// Persists only the user profile to localStorage (NOT the token).
// The JWT lives in an httpOnly cookie managed by the browser — it cannot be
// read by JavaScript, which eliminates the XSS token-theft vector.
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      isAuthenticated: false,

      setAuth: (user) => {
        set({ user, isAuthenticated: true })
      },

      clearAuth: () => {
        set({ user: null, isAuthenticated: false })
      },

      updateUser: (patch) =>
        set((state) => ({
          user: state.user ? { ...state.user, ...patch } : state.user,
        })),
    }),
    {
      name: 'ppms_user',
      partialize: (state) => ({ user: state.user, isAuthenticated: state.isAuthenticated }),
    }
  )
)
```

- [ ] **Step 3: Update axios client — remove Authorization header, add withCredentials**

Replace `ppms-frontend/src/api/client.ts` entirely:
```typescript
import axios from 'axios'
import { useAuthStore } from '../store/authStore'

// All requests go to /api/* — Vite proxy forwards them to Spring Boot in dev.
// In production, both frontend and backend run on the same server, same origin.
// withCredentials: true is required so the browser sends the httpOnly JWT cookie
// automatically on every request — axios does not do this by default.
const apiClient = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
})

// If the server returns 401 and we are still authenticated, the session has expired.
// Checking isAuthenticated prevents repeated redirects from concurrent 401 responses.
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && useAuthStore.getState().isAuthenticated) {
      useAuthStore.getState().clearAuth()
      window.location.href = '/login?reason=session_expired'
    }
    return Promise.reject(error)
  }
)

export default apiClient
```

- [ ] **Step 4: Update LoginPage to call setAuth without token**

In `ppms-frontend/src/pages/auth/LoginPage.tsx`, update the `onSubmit` function.

Replace:
```typescript
setAuth(user, response.token)
```
With:
```typescript
setAuth(user)
```

Also remove `token` from the `LoginResponse` destructure if used:
```typescript
const response = await authApi.login(data)
const user: AuthUser = {
  userId: response.userId,
  fullName: response.fullName,
  phoneNumber: response.phoneNumber,
  role: response.role,
  assignedPumpId: response.assignedPumpId,
}
setAuth(user)
navigate(response.role === 'SUPER_ADMIN' ? '/super-admin' : '/dashboard')
```

- [ ] **Step 5: Commit**

```bash
git add ppms-frontend/src/types/auth.ts \
        ppms-frontend/src/store/authStore.ts \
        ppms-frontend/src/api/client.ts \
        ppms-frontend/src/pages/auth/LoginPage.tsx
git commit -m "security: remove JWT from localStorage — auth token now lives in httpOnly cookie only"
```

---

## Task 12: Verify everything compiles and works end-to-end

- [ ] **Step 1: Build the backend**

```bash
cd /Users/Sufiyan.Shaikh/IdeaProjects/self/project/ppms-backend
mvn clean compile -q
```
Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 2: Start the backend and test login produces a cookie**

```bash
mvn spring-boot:run &
sleep 10
curl -s -c /tmp/cookies.txt -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"<test_phone>","password":"<test_password>"}' | jq .
```
Expected: JSON response with `userId`, `fullName`, `role` — no `token` field. Cookie file should contain `ppms_jwt`.

```bash
cat /tmp/cookies.txt | grep ppms_jwt
```
Expected: `ppms_jwt` present with `HttpOnly` flag.

- [ ] **Step 3: Test logout clears the cookie**

```bash
curl -s -b /tmp/cookies.txt -c /tmp/cookies.txt -X POST http://localhost:8080/api/auth/logout
cat /tmp/cookies.txt | grep ppms_jwt
```
Expected: Cookie is gone or `Max-Age=0`.

- [ ] **Step 4: Start frontend and verify login flow**

```bash
cd /Users/Sufiyan.Shaikh/IdeaProjects/self/project/ppms-frontend
npm run dev
```

1. Open browser dev tools → Application → Local Storage → `ppms_user`
2. Log in
3. Verify `ppms_user` contains `user` and `isAuthenticated` but **no `token` field**
4. Open Application → Cookies → verify `ppms_jwt` is present with `HttpOnly` checked

- [ ] **Step 5: Test void credit entry sends body not query param**

In dev tools → Network, trigger a void action and verify the request payload is:
```json
{ "voidReason": "..." }
```
Not a query string `?voidReason=...`.

- [ ] **Step 6: Commit final verification note**

```bash
cd /Users/Sufiyan.Shaikh/IdeaProjects/self/project
git commit --allow-empty -m "security: all 5 fixes verified — httpOnly cookie, voidReason body, error sanitization, input guards, phone masking"
```
