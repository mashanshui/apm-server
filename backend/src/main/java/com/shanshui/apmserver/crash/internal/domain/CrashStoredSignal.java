package com.shanshui.apmserver.crash.internal.domain;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Crash 模块内部统一读取 crash 与 app_start 公共字段的存储视图。 */
public sealed interface CrashStoredSignal permits CrashEvent, AppStartEvent {

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
    default boolean isCrash() { return this instanceof CrashEvent; }
    default boolean isAppStart() { return this instanceof AppStartEvent; }
    default String crashKind() { return this instanceof CrashEvent event ? event.crashKind() : null; }
    default Boolean crashFatal() { return this instanceof CrashEvent event ? event.fatal() : null; }
    default String crashExceptionType() { return this instanceof CrashEvent event ? event.exceptionType() : null; }
    default String crashFingerprint() { return this instanceof CrashEvent event ? event.fingerprint() : null; }
    default String fingerprintVersion() { return this instanceof CrashEvent event ? event.fingerprintVersion() : null; }
    default String symbolicationStatus() { return this instanceof CrashEvent event ? event.symbolicationStatus() : null; }
    default CrashPayload crash() { return this instanceof CrashEvent event ? event.payload() : null; }
}
