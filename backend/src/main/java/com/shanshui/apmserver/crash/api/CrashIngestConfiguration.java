package com.shanshui.apmserver.crash.api;

/** Crash 校验、脱敏和指纹所需的最小只读配置。 */
public interface CrashIngestConfiguration {
    int getSupportedSchemaVersion();
    int getMaxEventBytes();
    int getMaxMessageLength();
    int getMaxStackFrames();
    int getMaxThrowableChain();
    int getMaxPastDays();
    int getMaxFutureSkewMinutes();
    String getDeviceHashSalt();
}
