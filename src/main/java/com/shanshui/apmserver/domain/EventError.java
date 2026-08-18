package com.shanshui.apmserver.domain;

public record EventError(Integer index, String eventId, String code, String message, boolean retryable) {
}
