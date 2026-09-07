package com.shanshui.apmserver.jank.api;

import java.util.List;

/** 结构化 FPS 与挂起率接收所需的只读配置。 */
public interface JankMetricsConfiguration {
    int getSupportedSchemaVersion();
    int getMaxEventBytes();
    int getMaxPastDays();
    int getMaxFutureSkewMinutes();
    String getDeviceHashSalt();
    boolean isFrameMetricsEnabled();
    boolean isSuspensionMetricsEnabled();
    List<String> getSupportedFpsAlgorithmVersions();
    List<String> getSupportedSuspensionAlgorithmVersions();
    int getMaxSceneLength();
    int getMaxFrameHistogramBuckets();
    long getSuspensionThresholdMs();
}
