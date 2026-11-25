package com.shanshui.apmserver.ingest.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record EventBatchRequest(String requestId, List<EventEnvelope> events,
                                /** HTTP 接收器测得的每个原始事件字节数，不来自客户端字段。 */
                                @JsonIgnore List<Long> eventBytes) {
    /** 内部构造的批次没有原始线上字节数。 */
    public EventBatchRequest(String requestId, List<EventEnvelope> events) {
        this(requestId, events, null);
    }
}
