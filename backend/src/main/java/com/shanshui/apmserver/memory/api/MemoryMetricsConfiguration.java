package com.shanshui.apmserver.memory.api;

/** 内存采样接收所需的只读配置，避免 ingest 依赖配置实现类。 */
public interface MemoryMetricsConfiguration {

    int getSupportedSchemaVersion();

    int getMaxEventBytes();

    int getMaxPastDays();

    int getMaxFutureSkewMinutes();

    String getDeviceHashSalt();

    int getMaxProcessNameLength();

    int getMaxSceneLength();

    long getMaxMemoryMetricBytes();
}
