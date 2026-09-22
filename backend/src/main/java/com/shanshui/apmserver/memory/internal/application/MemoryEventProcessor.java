package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryEventProcessing;
import com.shanshui.apmserver.memory.api.MemoryIngestCommand;
import com.shanshui.apmserver.memory.internal.domain.MemoryEvent;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.telemetry.api.SignalIngestResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 将内存上传命令转换为领域事件并执行幂等写入。 */
@Service
public class MemoryEventProcessor implements MemoryEventProcessing {

    private final MemoryEventValidator validator;
    private final MemorySanitizer sanitizer;
    private final MemoryWriteCoordinator writeCoordinator;

    public MemoryEventProcessor(MemoryEventValidator validator, MemorySanitizer sanitizer,
                                MemoryWriteCoordinator writeCoordinator) {
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.writeCoordinator = writeCoordinator;
    }

    @Override
    public SignalIngestResult ingest(UUID appId, MemoryIngestCommand event, Instant receivedAt) {
        validator.validate(event);
        MemoryIngestCommand sanitized = sanitizer.sanitize(event);
        EventMetadata metadata = new EventMetadata(appId,
                sanitizer.sanitizeIdentifier(sanitized.packageName(), 255),
                sanitizer.sanitizeIdentifier(sanitized.eventId(), 128), sanitized.eventType(),
                Instant.ofEpochMilli(sanitized.occurredAt()), receivedAt, sanitized.schemaVersion(),
                // MemorySanitizer 已经完成匿名设备哈希，这里直接复用结果，避免同一设备因重复哈希产生不同 ID。
                sanitizer.sanitizeIdentifier(sanitized.sessionId(), 128), sanitized.processId(), sanitized.anonymousDeviceId(),
                sanitizer.sanitizeText(sanitized.appVersion(), 128), sanitized.versionCode(),
                sanitizer.sanitizeIdentifier(sanitized.buildId(), 256), sanitizer.sanitizeIdentifier(sanitized.environment(), 64),
                sanitizer.sanitizeIdentifier(sanitized.channel(), 128), sanitizer.sanitizeIdentifier(sanitized.osVersion(), 64),
                sanitizer.sanitizeText(sanitized.deviceModel(), 256), sanitizer.sanitizeIdentifier(sanitized.networkType(), 32),
                sanitized.measurements() == null ? Map.of() : sanitized.measurements(),
                sanitized.attributes() == null ? Map.of() : sanitized.attributes());
        MemoryEvent stored = new MemoryEvent(metadata, sanitized.memorySample());
        var appendResult = writeCoordinator.append(appId, java.util.List.of(stored));
        return new SignalIngestResult(appendResult, metadata, null);
    }
}
