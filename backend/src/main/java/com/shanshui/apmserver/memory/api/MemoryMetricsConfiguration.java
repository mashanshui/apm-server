package com.shanshui.apmserver.memory.api;

/** 内存采样接收所需的只读配置，避免 ingest 依赖配置实现类。 */
public interface MemoryMetricsConfiguration {

    int getSupportedSchemaVersion();

    int getMaxEventBytes();

    /** 峰值历史接收窗口，独立于其他领域。 */
    int getMaxMemoryPeakPastDays();

    int getMaxFutureSkewMinutes();

    String getDeviceHashSalt();

    int getMaxProcessNameLength();

    int getMaxSceneLength();

    long getMaxMemoryMetricBytes();
}
