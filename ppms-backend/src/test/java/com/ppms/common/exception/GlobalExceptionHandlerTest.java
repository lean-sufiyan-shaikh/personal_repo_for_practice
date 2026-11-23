package com.ppms.common.exception;

import com.ppms.auth.InvalidCredentialsException;
import com.ppms.auth.PasswordPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private static final String SECRET_DETAIL = "jdbc:postgresql://db-internal:5432/ppms password=hunter2";

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void lockWaitTimeoutAsksTheCallerToRetryWith409() throws Exception {
        mockMvc.perform(get("/test/lock-timeout"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Another update is in progress, please retry."));
    }

    @Test
    void unexpectedErrorReturns500WithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("jdbc"))));
    }

    @Test
    void malformedJsonBodyReturns400WithoutEchoingTheParserError() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"amount\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body. Check field types and enum values."));
    }

    @Test
    void parameterOfTheWrongTypeReturns400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/test/typed").param("count", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'count': many"));
    }

    @Test
    void missingRequiredParameterReturns400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/test/typed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter 'count' is missing"));
    }

    @Test
    void unsupportedHttpMethodReturns405() throws Exception {
        mockMvc.perform(post("/test/typed"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void recordFromAnotherPumpReturns404AndForbiddenActionReturns403() throws Exception {
        mockMvc.perform(get("/test/not-found")).andExpect(status().isNotFound());
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action"));
    }

    @Test
    void businessErrorWithACodeSendsTheCodeAndParamsBesideTheEnglishMessage() throws Exception {
        mockMvc.perform(get("/test/credit-limit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.params.clientName").value("Sharma Transport"))
                .andExpect(jsonPath("$.params.limit").value("50000.00"))
                .andExpect(jsonPath("$.message").value("Credit limit exceeded for 'Sharma Transport'."));
    }

    @Test
    void businessErrorWithoutACodeSendsOnlyTheMessage() throws Exception {
        mockMvc.perform(get("/test/plain-business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Shift is not open."))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.params").doesNotExist());
    }

    @Test
    void tooManyLoginAttemptsAndWrongCredentialsCarryTheirCodes() throws Exception {
        mockMvc.perform(get("/test/too-many"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"))
                .andExpect(jsonPath("$.params.minutes").value(15));
        mockMvc.perform(get("/test/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid phone number or password"));
    }

    @Test
    void weakPasswordIsReportedWithThePasswordPolicyCode() throws Exception {
        mockMvc.perform(post("/test/password").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"simple\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"))
                .andExpect(jsonPath("$.fieldErrors.password").value(PasswordPolicy.MESSAGE));
        mockMvc.perform(post("/test/password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/credit-limit")
        void creditLimit() {
            throw new BusinessException(ErrorCodes.CREDIT_LIMIT_EXCEEDED,
                    Map.of("clientName", "Sharma Transport", "limit", "50000.00"),
                    "Credit limit exceeded for 'Sharma Transport'.");
        }

        @GetMapping("/test/plain-business")
        void plainBusiness() {
            throw new BusinessException("Shift is not open.");
        }

        @GetMapping("/test/too-many")
        void tooMany() {
            throw new TooManyRequestsException(ErrorCodes.TOO_MANY_LOGIN_ATTEMPTS, Map.of("minutes", 15),
                    "Too many failed login attempts.");
        }

        @GetMapping("/test/bad-credentials")
        void badCredentials() {
            throw new InvalidCredentialsException("Invalid phone number or password");
        }

        @PostMapping("/test/password")
        void password(@Valid @RequestBody PasswordBody body) {
        }

        @GetMapping("/test/lock-timeout")
        void lockTimeout() {
            throw new CannotAcquireLockException("canceling statement due to lock timeout");
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException(SECRET_DETAIL);
        }

        @PostMapping("/test/body")
        void body(@RequestBody Map<String, Object> body) {
        }

        @GetMapping("/test/typed")
        void typed(@RequestParam int count) {
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Tank not found");
        }

        @GetMapping("/test/forbidden")
        void forbidden() {
            throw new org.springframework.security.access.AccessDeniedException(SECRET_DETAIL);
        }
    }

    record PasswordBody(
            @NotBlank @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE) String password) {
    }
}
