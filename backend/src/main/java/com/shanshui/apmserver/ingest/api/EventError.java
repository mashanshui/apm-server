package com.shanshui.apmserver.ingest.api;

public record EventError(Integer index, String eventId, String code, String message, boolean retryable) {
}
