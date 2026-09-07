package com.shanshui.apmserver.platform.api;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        String code,
        String message,
        boolean retryable,
        String requestId,
        List<?> errors,
        Instant timestamp) {

    public static ApiErrorResponse of(String code, String message, boolean retryable, String requestId) {
        return new ApiErrorResponse(code, message, retryable, requestId, List.of(), Instant.now());
    }
}
