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
    public CrashEventProcessor(CrashEventValidator validator,
                               CrashSanitizer sanitizer,
                               CrashFingerprintService fingerprintService,
                               CrashWritePort repository) {
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.fingerprintService = fingerprintService;
        this.repository = repository;
    }

    @Override
    public SignalIngestResult ingest(java.util.UUID appId, CrashIngestCommand event, Instant receivedAt) {
        validator.validate(event);
        CrashIngestCommand sanitized = sanitizer.sanitize(event);
        CrashPayload crash = sanitized.crash();
        String exceptionType = crash == null || crash.throwableChain().isEmpty()
                ? null : crash.throwableChain().get(0).type();
        String fingerprint = "crash".equals(sanitized.eventType())
                ? fingerprintService.fingerprint(appId, sanitized.packageName(), crash)
                : null;
        EventMetadata metadata = new EventMetadata(appId, sanitized.packageName(), sanitized.eventId(),
                sanitized.eventType(), Instant.ofEpochMilli(sanitized.occurredAt()), receivedAt,
                sanitized.schemaVersion(), sanitized.sessionId(), sanitized.anonymousDeviceId(),
                sanitized.appVersion(), sanitized.versionCode(), sanitized.buildId(), sanitized.environment(),
                sanitized.channel(), sanitized.osVersion(), sanitized.deviceModel(), sanitized.networkType(),
                sanitized.measurements() == null ? Map.of() : sanitized.measurements(),
                sanitized.attributes() == null ? Map.of() : sanitized.attributes());
        CrashStoredSignal stored = crash == null
                ? new AppStartEvent(metadata)
                : new CrashEvent(metadata, crash.kind(), Boolean.TRUE.equals(crash.fatal()), exceptionType,
                fingerprint, fingerprintService.version(), "raw_only", crash);
        var appendResult = repository.append(appId, java.util.List.of(stored));
        return new SignalIngestResult(appendResult, metadata, null);
    }
}
