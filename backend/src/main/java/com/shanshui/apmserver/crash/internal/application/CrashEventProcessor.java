package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashEventProcessing;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.internal.domain.AppStartEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.crash.internal.port.CrashWritePort;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.telemetry.api.SignalIngestResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class CrashEventProcessor implements CrashEventProcessing {

    private final CrashEventValidator validator;
    private final CrashSanitizer sanitizer;
    private final CrashFingerprintService fingerprintService;
    private final CrashWritePort repository;
    /** 当前原始链序列化。 */
    private final tools.jackson.databind.ObjectMapper mapper;
    /** 跨库通知失败由活动构建定时对账补偿。 */
    private final com.shanshui.apmserver.crash.api.CrashSymbolicationWork work;
    /** 只查可用性，不执行还原。 */
    private final com.shanshui.apmserver.symbol.api.SymbolRegistry symbols;
    public CrashEventProcessor(CrashEventValidator validator,
                               CrashSanitizer sanitizer,
                               CrashFingerprintService fingerprintService,
                               CrashWritePort repository,
                               tools.jackson.databind.ObjectMapper mapper,
                               com.shanshui.apmserver.crash.api.CrashSymbolicationWork work,
                               com.shanshui.apmserver.symbol.api.SymbolRegistry symbols) {
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.fingerprintService = fingerprintService;
        this.repository = repository;
        this.mapper = mapper;
        this.work = work;
        this.symbols = symbols;
    }

    @Override
    public SignalIngestResult ingest(java.util.UUID appId, CrashIngestCommand event, Instant receivedAt) {
        validator.validate(event);
        CrashIngestCommand sanitized = sanitizer.sanitize(event);
        CrashPayload crash = sanitized.crash();
        String exceptionType = crash == null || crash.throwableChain().isEmpty()
                ? null : crash.throwableChain().get(0).type();
        String fingerprint = "crash".equals(sanitized.eventType())
                ? fingerprintService.temporary(appId, sanitized.packageName(), sanitized.buildId(), crash)
                : null;
        EventMetadata metadata = new EventMetadata(appId, sanitized.packageName(), sanitized.eventId(),
                sanitized.eventType(), Instant.ofEpochMilli(sanitized.occurredAt()), receivedAt,
                sanitized.schemaVersion(), sanitized.sessionId(), sanitized.processId(), sanitized.anonymousDeviceId(),
                sanitized.appVersion(), sanitized.versionCode(), sanitized.buildId(), sanitized.environment(),
                sanitized.channel(), sanitized.osVersion(), sanitized.deviceModel(), sanitized.networkType(),
                sanitized.measurements() == null ? Map.of() : sanitized.measurements(),
                sanitized.attributes() == null ? Map.of() : sanitized.attributes());
        CrashStoredSignal stored = crash == null
                ? new AppStartEvent(metadata)
                : new CrashEvent(metadata, crash.kind(), Boolean.TRUE.equals(crash.fatal()),
                new com.shanshui.apmserver.crash.internal.domain.CrashState(appId, metadata.eventId(), metadata.buildId(), metadata.occurredAt(),
                        0, "pending", "awaiting_processing", "raw_chain", mapper.writeValueAsString(crash), exceptionType,
                        fingerprint, "tmp-v1", "temporary", null, null, null, null, null));
        var appendResult = repository.append(appId, java.util.List.of(stored));
        if (crash != null) {
            // 两侧已确认后通知失败不能伪造未持久化；定时轮转仍可发现当前事件。
            try (var mapping = symbols.acquire(appId, metadata.buildId()).orElse(null)) {
                if (mapping != null) work.wake(appId, metadata.buildId());
            } catch (Exception unavailable) {
                // 不记录含原始内容的异常；通知的可靠性由持久化轮转检查点补偿。
            }
        }
        return new SignalIngestResult(appendResult, metadata, null);
    }
}
