package com.shanshui.apmserver.bootstrap.internal.config;

import com.shanshui.apmserver.crash.api.CrashIngestConfiguration;
import com.shanshui.apmserver.ingest.api.IngestConfiguration;
import com.shanshui.apmserver.identity.api.IngestAccessConfiguration;
import com.shanshui.apmserver.jank.api.JankIngestConfiguration;
import com.shanshui.apmserver.jank.api.JankMetricsConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "apm.ingest")
public class IngestConfigurationProperties implements IngestConfiguration, CrashIngestConfiguration,
        JankIngestConfiguration, JankMetricsConfiguration, IngestAccessConfiguration {

    private boolean enabled = true;
    private int supportedSchemaVersion = 2;
    private int maxRequestBytes = 1024 * 1024;
    private int maxDecompressedBytes = 4 * 1024 * 1024;
    private int maxEventBytes = 256 * 1024;
    private int maxMessageLength = 4096;
    private int maxStackFrames = 200;
    private int maxThrowableChain = 16;
    private int maxPastDays = 7;
    private int maxFutureSkewMinutes = 15;
    private String deviceHashSalt = "local-development-salt";
    private boolean jankEnabled = true;
    private boolean frameMetricsEnabled = true;
    private boolean suspensionMetricsEnabled = true;
    private List<String> supportedJankAlgorithmVersions = new ArrayList<>(List.of("jank-v1"));
    private List<String> supportedFpsAlgorithmVersions = new ArrayList<>(List.of("fps-v1"));
    private List<String> supportedSuspensionAlgorithmVersions = new ArrayList<>(List.of("suspension-v1"));
    private int maxJankSamples = 2_000;
    private int maxJankStackDictionary = 2_000;
    private int maxJankStackDepth = 128;
    private int maxJankTotalFrames = 10_000;
    private int maxSceneLength = 128;
    private long maxJankMessageDurationNs = 60_000_000_000L;
    private long maxJankThresholdNs = 60_000_000_000L;
    private long maxSamplingIntervalNs = 5_000_000_000L;
    private int maxJankDetailBytes = 512 * 1024;
    private int maxFrameHistogramBuckets = 64;
    private long suspensionThresholdMs = 200L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSupportedSchemaVersion() {
        return supportedSchemaVersion;
    }

    public void setSupportedSchemaVersion(int supportedSchemaVersion) {
        this.supportedSchemaVersion = supportedSchemaVersion;
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public int getMaxDecompressedBytes() {
        return maxDecompressedBytes;
    }

    public void setMaxDecompressedBytes(int maxDecompressedBytes) {
        this.maxDecompressedBytes = maxDecompressedBytes;
    }

    public int getMaxEventBytes() {
        return maxEventBytes;
    }

    public void setMaxEventBytes(int maxEventBytes) {
        this.maxEventBytes = maxEventBytes;
    }

    public int getMaxMessageLength() {
        return maxMessageLength;
    }

    public void setMaxMessageLength(int maxMessageLength) {
        this.maxMessageLength = maxMessageLength;
    }

    public int getMaxStackFrames() {
        return maxStackFrames;
    }

    public void setMaxStackFrames(int maxStackFrames) {
        this.maxStackFrames = maxStackFrames;
    }

    public int getMaxThrowableChain() {
        return maxThrowableChain;
    }

    public void setMaxThrowableChain(int maxThrowableChain) {
        this.maxThrowableChain = maxThrowableChain;
    }

    public int getMaxPastDays() {
        return maxPastDays;
    }

    public void setMaxPastDays(int maxPastDays) {
        this.maxPastDays = maxPastDays;
    }

    public int getMaxFutureSkewMinutes() {
        return maxFutureSkewMinutes;
    }

    public void setMaxFutureSkewMinutes(int maxFutureSkewMinutes) {
        this.maxFutureSkewMinutes = maxFutureSkewMinutes;
    }

    public String getDeviceHashSalt() {
        return deviceHashSalt;
    }

    public void setDeviceHashSalt(String deviceHashSalt) {
        this.deviceHashSalt = deviceHashSalt;
    }

    public boolean isJankEnabled() {
        return jankEnabled;
    }

    public void setJankEnabled(boolean jankEnabled) {
        this.jankEnabled = jankEnabled;
    }

    public boolean isFrameMetricsEnabled() {
        return frameMetricsEnabled;
    }

    public void setFrameMetricsEnabled(boolean frameMetricsEnabled) {
        this.frameMetricsEnabled = frameMetricsEnabled;
    }

    public boolean isSuspensionMetricsEnabled() {
        return suspensionMetricsEnabled;
    }

    public void setSuspensionMetricsEnabled(boolean suspensionMetricsEnabled) {
        this.suspensionMetricsEnabled = suspensionMetricsEnabled;
    }

    public List<String> getSupportedJankAlgorithmVersions() {
        return supportedJankAlgorithmVersions;
    }

    public void setSupportedJankAlgorithmVersions(List<String> supportedJankAlgorithmVersions) {
        this.supportedJankAlgorithmVersions = supportedJankAlgorithmVersions == null
                ? new ArrayList<>() : new ArrayList<>(supportedJankAlgorithmVersions);
    }

    public List<String> getSupportedFpsAlgorithmVersions() {
        return supportedFpsAlgorithmVersions;
    }

    public void setSupportedFpsAlgorithmVersions(List<String> supportedFpsAlgorithmVersions) {
        this.supportedFpsAlgorithmVersions = supportedFpsAlgorithmVersions == null
                ? new ArrayList<>() : new ArrayList<>(supportedFpsAlgorithmVersions);
    }

    public List<String> getSupportedSuspensionAlgorithmVersions() {
        return supportedSuspensionAlgorithmVersions;
    }

    public void setSupportedSuspensionAlgorithmVersions(List<String> supportedSuspensionAlgorithmVersions) {
        this.supportedSuspensionAlgorithmVersions = supportedSuspensionAlgorithmVersions == null
                ? new ArrayList<>() : new ArrayList<>(supportedSuspensionAlgorithmVersions);
    }

    public int getMaxJankSamples() {
        return maxJankSamples;
    }

    public void setMaxJankSamples(int maxJankSamples) {
        this.maxJankSamples = maxJankSamples;
    }

    public int getMaxJankStackDictionary() {
        return maxJankStackDictionary;
    }

    public void setMaxJankStackDictionary(int maxJankStackDictionary) {
        this.maxJankStackDictionary = maxJankStackDictionary;
    }

    public int getMaxJankStackDepth() {
        return maxJankStackDepth;
    }

    public void setMaxJankStackDepth(int maxJankStackDepth) {
        this.maxJankStackDepth = maxJankStackDepth;
    }

    public int getMaxJankTotalFrames() {
        return maxJankTotalFrames;
    }

    public void setMaxJankTotalFrames(int maxJankTotalFrames) {
        this.maxJankTotalFrames = maxJankTotalFrames;
    }

    public int getMaxSceneLength() {
        return maxSceneLength;
    }

    public void setMaxSceneLength(int maxSceneLength) {
        this.maxSceneLength = maxSceneLength;
    }

    public long getMaxJankMessageDurationNs() {
        return maxJankMessageDurationNs;
    }

    public void setMaxJankMessageDurationNs(long maxJankMessageDurationNs) {
        this.maxJankMessageDurationNs = maxJankMessageDurationNs;
    }

    public long getMaxJankThresholdNs() {
        return maxJankThresholdNs;
    }

    public void setMaxJankThresholdNs(long maxJankThresholdNs) {
        this.maxJankThresholdNs = maxJankThresholdNs;
    }

    public long getMaxSamplingIntervalNs() {
        return maxSamplingIntervalNs;
    }

    public void setMaxSamplingIntervalNs(long maxSamplingIntervalNs) {
        this.maxSamplingIntervalNs = maxSamplingIntervalNs;
    }

    public int getMaxJankDetailBytes() {
        return maxJankDetailBytes;
    }

    public void setMaxJankDetailBytes(int maxJankDetailBytes) {
        this.maxJankDetailBytes = maxJankDetailBytes;
    }

    public int getMaxFrameHistogramBuckets() {
        return maxFrameHistogramBuckets;
    }

    public void setMaxFrameHistogramBuckets(int maxFrameHistogramBuckets) {
        this.maxFrameHistogramBuckets = maxFrameHistogramBuckets;
    }

    public long getSuspensionThresholdMs() {
        return suspensionThresholdMs;
    }

    public void setSuspensionThresholdMs(long suspensionThresholdMs) {
        this.suspensionThresholdMs = suspensionThresholdMs;
    }
}
