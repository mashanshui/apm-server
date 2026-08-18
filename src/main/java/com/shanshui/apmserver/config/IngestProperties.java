package com.shanshui.apmserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.ingest")
public class IngestProperties {

    private boolean enabled = true;
    private String projectId = "demo-project";
    private String projectKey = "local-demo-key";
    private String defaultAppId = "demo-app";
    private int supportedSchemaVersion = 1;
    private int maxRequestBytes = 1024 * 1024;
    private int maxDecompressedBytes = 4 * 1024 * 1024;
    private int maxEventBytes = 256 * 1024;
    private int maxMessageLength = 4096;
    private int maxStackFrames = 200;
    private int maxThrowableChain = 16;
    private int maxPastDays = 7;
    private int maxFutureSkewMinutes = 15;
    private String deviceHashSalt = "local-development-salt";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getDefaultAppId() {
        return defaultAppId;
    }

    public void setDefaultAppId(String defaultAppId) {
        this.defaultAppId = defaultAppId;
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
}
