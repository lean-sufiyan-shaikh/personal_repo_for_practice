package com.ppms.auth;

import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.support.PostgresTestContainerConfig;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AuthSecurityIntegrationTest {

    private static final String PASSWORD = "Secret@123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private LoginAttemptRepository loginAttemptRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User ownerA;
    private PumpLocation pumpA;
    private PumpLocation pumpB;

    @BeforeEach
    void createTwoOwnersWithOnePumpEach() {
        loginAttemptRepository.deleteAll();
        ownerA = saveOwner();
        User ownerB = saveOwner();
        pumpA = savePump(ownerA);
        pumpB = savePump(ownerB);
    }

    @Test
    void ownerCannotReadAnotherOwnersPump() throws Exception {
        Cookie cookie = loginAs(ownerA);

        mockMvc.perform(get("/api/pumps/{pumpId}/audit-logs", pumpB.getId()).cookie(cookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pumps/{pumpId}/audit-logs", pumpA.getId()).cookie(cookie))
                .andExpect(status().isOk());
    }

    @Test
    void percentEncodedPumpIdCannotSkipTheAccessCheck() throws Exception {
        Cookie cookie = loginAs(ownerA);
        String encodedPumpId = pumpB.getId().toString().chars()
                .mapToObj(digit -> "%3" + (char) digit)
                .collect(Collectors.joining());

        mockMvc.perform(get(URI.create("/api/pumps/" + encodedPumpId + "/audit-logs")).cookie(cookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCookieDoesNotBlockLogin() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .cookie(new Cookie(JwtCookieSupport.COOKIE_NAME, "not-a-valid-jwt"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ownerA.getPhoneNumber(), PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void invalidCookieOnProtectedEndpointReturns401AndClearsCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/pumps")
                        .cookie(new Cookie(JwtCookieSupport.COOKIE_NAME, "not-a-valid-jwt")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void tokenIsRejectedAfterTokenVersionChanges() throws Exception {
        Cookie cookie = loginAs(ownerA);
        mockMvc.perform(get("/api/pumps").cookie(cookie)).andExpect(status().isOk());

        User reloaded = userRepository.findById(ownerA.getId()).orElseThrow();
        reloaded.setTokenVersion(reloaded.getTokenVersion() + 1);
        userRepository.save(reloaded);

        mockMvc.perform(get("/api/pumps").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void sixthFailedLoginForSamePhoneReturns429() throws Exception {
        for (int attempt = 0; attempt < LoginRateLimiterService.MAX_FAILURES_PER_PHONE; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(ownerA.getPhoneNumber(), "Wrong@123")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ownerA.getPhoneNumber(), PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    private Cookie loginAs(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(user.getPhoneNumber(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String token = setCookie.substring(
                setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        return new Cookie(JwtCookieSupport.COOKIE_NAME, token);
    }

    private String loginBody(String phoneNumber, String password) {
        return "{\"phoneNumber\":\"" + phoneNumber + "\",\"password\":\"" + password + "\"}";
    }

    private User saveOwner() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .employeeId("EMP-" + unique)
                .fullName("Owner " + unique)
                .phoneNumber(randomPhoneNumber())
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(UserRole.OWNER)
                .status(UserStatus.ACTIVE)
                .dateOfJoining(LocalDate.now())
                .build());
    }

    private PumpLocation savePump(User owner) {
        return pumpLocationRepository.save(PumpLocation.builder()
                .ownerId(owner.getId())
                .name("Pump of " + owner.getFullName())
                .address("Test address")
                .maxDuCount(4)
                .build());
    }

    private String randomPhoneNumber() {
        long number = 6_000_000_000L + (long) (Math.random() * 3_999_999_999L);
        return Long.toString(number);
    }
}
