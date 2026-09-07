package com.shanshui.apmserver.jank.api;

import java.util.List;

/** Jank ZIP、分析和证据约束的只读配置。 */
public interface JankIngestConfiguration extends JankMetricsConfiguration {
    boolean isJankEnabled();
    List<String> getSupportedJankAlgorithmVersions();
    int getMaxJankSamples();
    int getMaxJankStackDictionary();
    int getMaxJankStackDepth();
    int getMaxJankTotalFrames();
    long getMaxJankMessageDurationNs();
    long getMaxJankThresholdNs();
    long getMaxSamplingIntervalNs();
    int getMaxJankDetailBytes();
    int getMaxMessageLength();
}
