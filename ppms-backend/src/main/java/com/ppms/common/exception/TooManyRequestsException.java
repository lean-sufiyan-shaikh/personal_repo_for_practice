package com.ppms.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class TooManyRequestsException extends RuntimeException {

    private final String code;
    private final Map<String, Object> params;

    public TooManyRequestsException(String message) {
        this(null, Map.of(), message);
    }

    public TooManyRequestsException(String code, Map<String, Object> params, String englishMessage) {
        super(englishMessage);
        this.code = code;
        this.params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getParams() {
        return params;
    }
}
