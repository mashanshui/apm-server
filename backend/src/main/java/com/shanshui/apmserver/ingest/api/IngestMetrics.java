package com.shanshui.apmserver.ingest.api;

import java.time.Duration;

/** 公共接收链路指标端口。 */
public interface IngestMetrics {
    void received(int count);
    void rejected(int count);
    void duplicate(int count);
    void retryableFailure();
    void uploadDelay(Duration delay);
}
