package com.shanshui.apmserver.domain;

import java.util.List;

public record BatchIngestResponse(
        String requestId,
        int accepted,
        int rejected,
        int duplicate,
        boolean retryable,
        Integer retryAfterSeconds,
        List<EventError> errors) {

    public static BatchIngestResponse accepted(String requestId, int accepted, int rejected, int duplicate,
                                               List<EventError> errors) {
        return new BatchIngestResponse(requestId, accepted, rejected, duplicate,
                false, null, List.copyOf(errors));
    }

    public static BatchIngestResponse retryable(String requestId, int retryAfterSeconds) {
        return new BatchIngestResponse(requestId, 0, 0, 0, true, retryAfterSeconds, List.of());
    }
}
