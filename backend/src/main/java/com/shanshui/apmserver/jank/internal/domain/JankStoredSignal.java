package com.shanshui.apmserver.jank.internal.domain;

import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.JankAnalysis;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Jank 模块内部统一读取个例与两类指标事件公共字段的存储视图。 */
public sealed interface JankStoredSignal permits JankEvent, FrameSceneSummary, ForegroundSuspensionSummary {

    EventMetadata metadata();

    default UUID appId() { return metadata().appId(); }
    default String packageName() { return metadata().packageName(); }
    default String eventId() { return metadata().eventId(); }
    default String eventType() { return metadata().eventType(); }
    default Instant occurredAt() { return metadata().occurredAt(); }
    default Instant receivedAt() { return metadata().receivedAt(); }
    default int schemaVersion() { return metadata().schemaVersion(); }
    default String sessionId() { return metadata().sessionId(); }
    default String processId() { return metadata().processId(); }
    default String anonymousDeviceId() { return metadata().anonymousDeviceId(); }
    default String appVersion() { return metadata().appVersion(); }
    default Integer versionCode() { return metadata().versionCode(); }
    default String buildId() { return metadata().buildId(); }
    default String environment() { return metadata().environment(); }
    default String channel() { return metadata().channel(); }
    default String osVersion() { return metadata().osVersion(); }
    default String deviceModel() { return metadata().deviceModel(); }
    default String networkType() { return metadata().networkType(); }
    default Map<String, Object> measurements() { return metadata().measurements(); }
    default Map<String, Object> attributes() { return metadata().attributes(); }
    default boolean isJank() { return this instanceof JankEvent; }
    default boolean isFrameSceneSummary() { return this instanceof FrameSceneSummary; }
    default boolean isForegroundSuspensionSummary() { return this instanceof ForegroundSuspensionSummary; }
    default String crashFingerprint() { return this instanceof JankEvent event ? event.fingerprint() : null; }
    default String fingerprintVersion() { return this instanceof JankEvent event ? event.fingerprintVersion() : null; }
    default String symbolicationStatus() { return this instanceof JankEvent event ? event.symbolicationStatus() : null; }
    default JankPayload jank() { return this instanceof JankEvent event ? event.payload() : null; }
    default JankAnalysis jankAnalysis() { return this instanceof JankEvent event ? event.analysis() : null; }
    default FrameSceneSummaryPayload frameSceneSummary() {
        return this instanceof FrameSceneSummary event ? event.payload() : null;
    }
    default ForegroundSuspensionSummaryPayload foregroundSuspensionSummary() {
        return this instanceof ForegroundSuspensionSummary event ? event.payload() : null;
    }
}
