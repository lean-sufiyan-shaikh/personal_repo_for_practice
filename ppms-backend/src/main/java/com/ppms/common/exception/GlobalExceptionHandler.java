package com.ppms.common.exception;

import com.ppms.auth.InvalidCredentialsException;
import com.ppms.fuel.PriceDeviationException;
import com.ppms.fuel.PriceDeviationWarning;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, ex.getMessage()));
    }

    /**
     * HTTP 409 Conflict — fuel price deviates >15% and requires explicit confirmation.
     * Frontend must show a warning dialog and re-submit with confirmed=true.
     */
    @ExceptionHandler(PriceDeviationException.class)
    public ResponseEntity<PriceDeviationWarning> handlePriceDeviation(PriceDeviationException ex) {
        String msg = String.format(
                "New price deviates %.2f%% from last price (₹%.4f → ₹%.4f). Please confirm this is correct.",
                ex.getDeviationPercent(), ex.getLastPrice(), ex.getNewPrice());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new PriceDeviationWarning(msg, ex.getLastPrice(), ex.getNewPrice(), ex.getDeviationPercent()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.ofValidation(fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"));
    }

    public record ErrorResponse(
            int status,
            String message,
            Map<String, String> fieldErrors,
            OffsetDateTime timestamp
    ) {
        static ErrorResponse of(HttpStatus status, String message) {
            return new ErrorResponse(status.value(), message, null, OffsetDateTime.now());
        }

        static ErrorResponse ofValidation(Map<String, String> fieldErrors) {
            return new ErrorResponse(400, "Validation failed", fieldErrors, OffsetDateTime.now());
        }
    }
}
