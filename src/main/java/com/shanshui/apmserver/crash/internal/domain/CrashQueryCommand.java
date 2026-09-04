package com.shanshui.apmserver.crash.internal.domain;

/** Crash 查询应用层使用的不可变命令。 */
public record CrashQueryCommand(
        String appVersion,
        String channel,
        String environment,
        String osVersion,
        String deviceModel,
        String fingerprint,
        Integer limit,
        String cursor,
        Long timeoutMs) {

    public static CrashQueryCommand empty() {
        return new CrashQueryCommand(null, null, null, null, null, null, null, null, null);
    }

    public CrashQueryCommand withAppVersion(String value) { return new CrashQueryCommand(value, channel, environment, osVersion, deviceModel, fingerprint, limit, cursor, timeoutMs); }
    public CrashQueryCommand withChannel(String value) { return new CrashQueryCommand(appVersion, value, environment, osVersion, deviceModel, fingerprint, limit, cursor, timeoutMs); }
    public CrashQueryCommand withEnvironment(String value) { return new CrashQueryCommand(appVersion, channel, value, osVersion, deviceModel, fingerprint, limit, cursor, timeoutMs); }
    public CrashQueryCommand withOsVersion(String value) { return new CrashQueryCommand(appVersion, channel, environment, value, deviceModel, fingerprint, limit, cursor, timeoutMs); }
    public CrashQueryCommand withDeviceModel(String value) { return new CrashQueryCommand(appVersion, channel, environment, osVersion, value, fingerprint, limit, cursor, timeoutMs); }
    public CrashQueryCommand withFingerprint(String value) { return new CrashQueryCommand(appVersion, channel, environment, osVersion, deviceModel, value, limit, cursor, timeoutMs); }
    public CrashQueryCommand withLimit(Integer value) { return new CrashQueryCommand(appVersion, channel, environment, osVersion, deviceModel, fingerprint, value, cursor, timeoutMs); }
    public CrashQueryCommand withCursor(String value) { return new CrashQueryCommand(appVersion, channel, environment, osVersion, deviceModel, fingerprint, limit, value, timeoutMs); }
    public CrashQueryCommand withTimeoutMs(Long value) { return new CrashQueryCommand(appVersion, channel, environment, osVersion, deviceModel, fingerprint, limit, cursor, value); }
}
