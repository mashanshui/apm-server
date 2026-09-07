package com.shanshui.apmserver.ingest.api;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.JankPayload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record EventEnvelope(
        Integer schemaVersion,
        String eventId,
        String eventType,
        Long occurredAt,
        String sessionId,
        String anonymousDeviceId,
        String packageName,
        String appVersion,
        Integer versionCode,
        String buildId,
        String environment,
        String channel,
        String osVersion,
        String deviceModel,
        String networkType,
        Map<String, Object> measurements,
        Map<String, Object> attributes,
        CrashPayload crash,
        JankPayload jank,
        FrameSceneSummaryPayload frameSceneSummary,
        ForegroundSuspensionSummaryPayload foregroundSuspensionSummary) {

    /**
     * 保留 JVM Crash/启动事件原有构造签名，避免已有客户端和测试因新增互斥载荷字段而破坏编译兼容性。
     */
    public EventEnvelope(Integer schemaVersion,
                         String eventId,
                         String eventType,
                         Long occurredAt,
                         String sessionId,
                         String anonymousDeviceId,
                         String packageName,
                         String appVersion,
                         Integer versionCode,
                         String buildId,
                         String environment,
                         String channel,
                         String osVersion,
                         String deviceModel,
                         String networkType,
                         Map<String, Object> measurements,
                         Map<String, Object> attributes,
                         CrashPayload crash) {
        this(schemaVersion, eventId, eventType, occurredAt, sessionId, anonymousDeviceId,
                packageName, appVersion, versionCode, buildId, environment, channel, osVersion,
                deviceModel, networkType, measurements, attributes, crash, null, null, null);
    }
}
