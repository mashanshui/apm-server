package com.shanshui.apmserver.jank.internal.application;

import com.shanshui.apmserver.jank.api.JankMetricEventProcessing;
import com.shanshui.apmserver.jank.api.JankMetricIngestCommand;
import com.shanshui.apmserver.jank.internal.domain.ForegroundSuspensionSummary;
import com.shanshui.apmserver.jank.internal.domain.FrameSceneSummary;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.telemetry.api.SignalIngestResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** 将结构化指标命令转换为 Jank 模块存储模型。 */
@Service
public class JankMetricEventProcessor implements JankMetricEventProcessing {

    private final JankMetricEventValidator validator;
    private final JankSanitizer sanitizer;
    private final JankWriteCoordinator writeCoordinator;

    public JankMetricEventProcessor(JankMetricEventValidator validator, JankSanitizer sanitizer,
                                    JankWriteCoordinator writeCoordinator) {
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.writeCoordinator = writeCoordinator;
    }

    @Override
    public SignalIngestResult ingest(java.util.UUID appId, JankMetricIngestCommand event, Instant receivedAt) {
        validator.validate(event);
        var frame = event.frameSceneSummary() == null ? null : sanitizer.sanitizeFrameScene(event.frameSceneSummary());
        var suspension = event.foregroundSuspensionSummary() == null
                ? null : sanitizer.sanitizeSuspension(event.foregroundSuspensionSummary());
        EventMetadata metadata = new EventMetadata(appId, sanitizer.sanitizeIdentifier(event.packageName(), 255),
                sanitizer.sanitizeIdentifier(event.eventId(), 128), event.eventType(),
                Instant.ofEpochMilli(event.occurredAt()), receivedAt, event.schemaVersion(),
                sanitizer.sanitizeIdentifier(event.sessionId(), 128), sanitizer.hashDeviceId(event.anonymousDeviceId()),
                sanitizer.sanitizeText(event.appVersion(), 128), event.versionCode(),
                sanitizer.sanitizeText(event.buildId(), 256), sanitizer.sanitizeIdentifier(event.environment(), 64),
                sanitizer.sanitizeIdentifier(event.channel(), 128), sanitizer.sanitizeIdentifier(event.osVersion(), 64),
                sanitizer.sanitizeText(event.deviceModel(), 256), sanitizer.sanitizeIdentifier(event.networkType(), 32),
                event.measurements() == null ? Map.of() : Map.copyOf(event.measurements()),
                event.attributes() == null ? Map.of() : Map.copyOf(event.attributes()));
        JankStoredSignal stored = frame != null
                ? new FrameSceneSummary(metadata, frame)
                : new ForegroundSuspensionSummary(metadata, suspension);
        var appendResult = writeCoordinator.append(appId, java.util.List.of(stored));
        String algorithmVersion = frame != null ? frame.algorithmVersion() : suspension.algorithmVersion();
        return new SignalIngestResult(appendResult, metadata, algorithmVersion);
    }
}
