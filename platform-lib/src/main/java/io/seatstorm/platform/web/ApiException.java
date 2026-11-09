package io.seatstorm.platform.web;

import java.util.Map;

/** Throw from any REST resource/handler; {@link ApiExceptionMapper} renders it as the error model. */
public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
