package com.shanshui.apmserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.query")
public class QueryProperties {

    private int maxRangeDays = 31;
    private int defaultLimit = 50;
    private int maxLimit = 500;
    private long defaultTimeoutMs = 2000;
    private long maxTimeoutMs = 5000;
    private boolean requireProjectHeader = true;

    public int getMaxRangeDays() {
        return maxRangeDays;
    }

    public void setMaxRangeDays(int maxRangeDays) {
        this.maxRangeDays = maxRangeDays;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getMaxTimeoutMs() {
        return maxTimeoutMs;
    }

    public void setMaxTimeoutMs(long maxTimeoutMs) {
        this.maxTimeoutMs = maxTimeoutMs;
    }

    public boolean isRequireProjectHeader() {
        return requireProjectHeader;
    }

    public void setRequireProjectHeader(boolean requireProjectHeader) {
        this.requireProjectHeader = requireProjectHeader;
    }
}
