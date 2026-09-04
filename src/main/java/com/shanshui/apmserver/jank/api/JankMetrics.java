package com.shanshui.apmserver.jank.api;

import java.time.Duration;

/** Jank 接收、版本、可见延迟和多表一致性指标端口。 */
public interface JankMetrics {
    void jankReceived();
    void jankAccepted(int count);
    void jankRejected();
    void jankDuplicate(int count);
    void jankSchemaVersion(Integer version);
    void jankAlgorithmVersion(String eventType, String version);
    void jankSchemaRejected();
    void jankAlgorithmRejected();
    void jankVisibleDelay(Duration delay);
    void jankFactDetailInconsistency();
    void retryableFailure();
}
