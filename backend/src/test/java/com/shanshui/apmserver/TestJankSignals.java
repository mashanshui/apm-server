package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.JankAnalysis;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.internal.domain.ForegroundSuspensionSummary;
import com.shanshui.apmserver.jank.internal.domain.FrameSceneSummary;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.telemetry.api.EventMetadata;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 测试数据工厂，用于以领域专属类型构造卡顿与指标事件。 */
final class TestJankSignals {

    private TestJankSignals() {
    }

    static JankStoredSignal storedEvent(
            UUID appId, String packageName, String eventId, String eventType, Instant occurredAt, Instant receivedAt,
            int schemaVersion, String sessionId, String anonymousDeviceId, String appVersion, Integer versionCode,
            String buildId, String environment, String channel, String osVersion, String deviceModel,
            String networkType, Map<String, Object> measurements, Map<String, Object> attributes,
            String crashKind, Boolean crashFatal, String crashExceptionType, String fingerprint,
            String fingerprintVersion, String symbolicationStatus, CrashPayload crash, JankPayload jank,
            JankAnalysis analysis, FrameSceneSummaryPayload frame, ForegroundSuspensionSummaryPayload suspension) {
        EventMetadata metadata = new EventMetadata(appId, packageName, eventId, eventType, occurredAt, receivedAt,
                schemaVersion, sessionId, CrashTestSupport.PROCESS_ID, anonymousDeviceId, appVersion, versionCode, buildId, environment, channel,
                osVersion, deviceModel, networkType, measurements, attributes);
        return switch (eventType) {
            case "jank" -> new JankEvent(metadata, fingerprint, fingerprintVersion, symbolicationStatus, jank, analysis);
            case "frame_scene_summary" -> new FrameSceneSummary(metadata, frame);
            case "foreground_suspension_summary" -> new ForegroundSuspensionSummary(metadata, suspension);
            default -> throw new IllegalArgumentException("不支持的 Jank 测试事件类型: " + eventType);
        };
    }
}
