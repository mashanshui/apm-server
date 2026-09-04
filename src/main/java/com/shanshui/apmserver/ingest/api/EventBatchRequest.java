package com.shanshui.apmserver.ingest.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record EventBatchRequest(String requestId, List<EventEnvelope> events) {
}
