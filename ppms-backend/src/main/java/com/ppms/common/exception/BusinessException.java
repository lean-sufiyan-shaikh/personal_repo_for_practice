package com.ppms.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// Thrown when a business rule is violated (e.g., shift already open, credit limit exceeded).
// Maps to HTTP 422 Unprocessable Entity so the frontend can distinguish it from a 400 (bad input).
public class BusinessException extends RuntimeException implements CodedError {

    private final String code;
    private final Map<String, Object> params;

    /** code names the error for translated screens; params fill its placeholders; message stays the English text. */
    public BusinessException(String code, Map<String, Object> params, String englishMessage) {
        super(englishMessage);
        this.code = code;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public Map<String, Object> getParams() {
        return params;
    }
}
