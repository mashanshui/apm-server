package com.shanshui.apmserver.platform.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apm.query")
public class QueryProperties {

    private int maxRangeDays = 31;
    private int defaultLimit = 50;
    private int maxLimit = 500;
    private long defaultTimeoutMs = 2000;
    private long maxTimeoutMs = 5000;
    /** 数据库每次最多读取的原始行数。 */
    private long maxRowsToRead = 5_000_000;
    /** 数据库每次最多读取的原始字节数。 */
    private long maxBytesToRead = 512L * 1024 * 1024;
    /** 数据库单查询内存上限。 */
    private long maxMemoryUsage = 256L * 1024 * 1024;
    /** HTTP 响应最大字节数，超出时明确报错。 */
    private int maxResponseBytes = 8 * 1024 * 1024;

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

    public long getMaxRowsToRead() { return maxRowsToRead; }
    public void setMaxRowsToRead(long value) { maxRowsToRead = value; }
    public long getMaxBytesToRead() { return maxBytesToRead; }
    public void setMaxBytesToRead(long value) { maxBytesToRead = value; }
    public long getMaxMemoryUsage() { return maxMemoryUsage; }
    public void setMaxMemoryUsage(long value) { maxMemoryUsage = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { maxResponseBytes = value; }

}
