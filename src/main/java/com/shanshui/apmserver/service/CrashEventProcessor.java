package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.StoredEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class CrashEventProcessor {

    private final CrashEventValidator validator;
    private final CrashSanitizer sanitizer;
    private final CrashFingerprintService fingerprintService;
    private final IngestProperties properties;

    public CrashEventProcessor(CrashEventValidator validator,
                               CrashSanitizer sanitizer,
                               CrashFingerprintService fingerprintService,
                               IngestProperties properties) {
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.fingerprintService = fingerprintService;
        this.properties = properties;
    }

    public StoredEvent process(String projectId, EventEnvelope event, Instant receivedAt) {
        validator.validate(event);
        EventEnvelope sanitized = sanitizer.sanitize(event);
        CrashPayload crash = sanitized.crash();
        String exceptionType = crash == null || crash.throwableChain().isEmpty()
                ? null : crash.throwableChain().get(0).type();
        String fingerprint = "crash".equals(sanitized.eventType())
                ? fingerprintService.fingerprint(projectId, sanitized.appId(), crash)
                : null;
        return new StoredEvent(
                projectId,
                sanitized.appId() == null ? properties.getDefaultAppId() : sanitized.appId(),
                sanitized.eventId(),
                sanitized.eventType(),
                Instant.ofEpochMilli(sanitized.occurredAt()),
                receivedAt,
                sanitized.schemaVersion(),
                sanitized.sessionId(),
                sanitized.anonymousDeviceId(),
                sanitized.appVersion(),
                sanitized.versionCode(),
                sanitized.buildId(),
                sanitized.environment(),
                sanitized.channel(),
                sanitized.osVersion(),
                sanitized.deviceModel(),
                sanitized.networkType(),
                sanitized.measurements() == null ? Map.of() : sanitized.measurements(),
                sanitized.attributes() == null ? Map.of() : sanitized.attributes(),
                crash == null ? null : crash.kind(),
                crash == null ? null : crash.fatal(),
                exceptionType,
                fingerprint,
                fingerprint == null ? null : fingerprintService.version(),
                fingerprint == null ? null : "raw_only",
                crash);
    }
}
