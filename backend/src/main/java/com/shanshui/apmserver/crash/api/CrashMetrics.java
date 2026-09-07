package com.shanshui.apmserver.crash.api;

import java.time.Duration;

/** Crash 可见延迟和统计质量指标端口。 */
public interface CrashMetrics {
    void denominatorInsufficient();
    void visibleDelay(Duration delay);
}
