package com.shanshui.apmserver.jank.internal.domain;

/** Jank 个例与指标查询共用的不可变应用层命令。 */
public record JankQueryCommand(
        String appVersion,
        String channel,
        String environment,
        String osVersion,
        String deviceModel,
        String fingerprint,
        String scene,
        String algorithmVersion,
        Integer limit,
        String cursor,
        Long timeoutMs) {

    public static JankQueryCommand empty() {
        return new JankQueryCommand(null, null, null, null, null, null, null, null, null, null, null);
    }

    public JankQueryCommand withAppVersion(String value) { return copy(value, channel, environment, osVersion, deviceModel, fingerprint, scene, algorithmVersion, limit, cursor, timeoutMs); }
    public JankQueryCommand withChannel(String value) { return copy(appVersion, value, environment, osVersion, deviceModel, fingerprint, scene, algorithmVersion, limit, cursor, timeoutMs); }
    public JankQueryCommand withEnvironment(String value) { return copy(appVersion, channel, value, osVersion, deviceModel, fingerprint, scene, algorithmVersion, limit, cursor, timeoutMs); }
    public JankQueryCommand withOsVersion(String value) { return copy(appVersion, channel, environment, value, deviceModel, fingerprint, scene, algorithmVersion, limit, cursor, timeoutMs); }
    public JankQueryCommand withDeviceModel(String value) { return copy(appVersion, channel, environment, osVersion, value, fingerprint, scene, algorithmVersion, limit, cursor, timeoutMs); }
    public JankQueryCommand withFingerprint(String value) { return copy(appVersion, channel, environment, osVersion, deviceModel, value, scene, algorithmVersion, limit, cursor, timeoutMs); }
    public JankQueryCommand withScene(String value) { return copy(appVersion, channel, environment, osVersion, deviceModel, fingerprint, value, algorithmVersion, limit, cursor, timeoutMs); }
    public JankQueryCommand withAlgorithmVersion(String value) { return copy(appVersion, channel, environment, osVersion, deviceModel, fingerprint, scene, value, limit, cursor, timeoutMs); }
    public JankQueryCommand withLimit(Integer value) { return copy(appVersion, channel, environment, osVersion, deviceModel, fingerprint, scene, algorithmVersion, value, cursor, timeoutMs); }
    public JankQueryCommand withCursor(String value) { return copy(appVersion, channel, environment, osVersion, deviceModel, fingerprint, scene, algorithmVersion, limit, value, timeoutMs); }
    public JankQueryCommand withTimeoutMs(Long value) { return copy(appVersion, channel, environment, osVersion, deviceModel, fingerprint, scene, algorithmVersion, limit, cursor, value); }

    private JankQueryCommand copy(String appVersion, String channel, String environment, String osVersion,
                                  String deviceModel, String fingerprint, String scene, String algorithmVersion,
                                  Integer limit, String cursor, Long timeoutMs) {
        return new JankQueryCommand(appVersion, channel, environment, osVersion, deviceModel, fingerprint,
                scene, algorithmVersion, limit, cursor, timeoutMs);
    }
}
