package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.JankAnalysis;
import com.shanshui.apmserver.domain.StoredEvent;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

@Service
public class CrashEventProcessor {

    private final CrashEventValidator validator;
    private final CrashSanitizer sanitizer;
    private final CrashFingerprintService fingerprintService;
    private final JankAnalysisService jankAnalysisService;
    private final JankFingerprintService jankFingerprintService;
    private final IngestProperties properties;

    @Autowired
    public CrashEventProcessor(CrashEventValidator validator,
                               CrashSanitizer sanitizer,
                               CrashFingerprintService fingerprintService,
                               JankAnalysisService jankAnalysisService,
                               JankFingerprintService jankFingerprintService,
                               IngestProperties properties) {
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.fingerprintService = fingerprintService;
        this.jankAnalysisService = jankAnalysisService;
        this.jankFingerprintService = jankFingerprintService;
        this.properties = properties;
    }

    /** 兼容既有 Crash 单元测试和扩展点。 */
    public CrashEventProcessor(CrashEventValidator validator,
                               CrashSanitizer sanitizer,
                               CrashFingerprintService fingerprintService,
                               IngestProperties properties) {
        this(validator, sanitizer, fingerprintService, new JankAnalysisService(properties),
                new JankFingerprintService(), properties);
    }

    public StoredEvent process(java.util.UUID appId, EventEnvelope event, Instant receivedAt) {
        validator.validate(event);
        EventEnvelope sanitized = sanitizer.sanitize(event);
        CrashPayload crash = sanitized.crash();
        String exceptionType = crash == null || crash.throwableChain().isEmpty()
                ? null : crash.throwableChain().get(0).type();
        String fingerprint = "crash".equals(sanitized.eventType())
                ? fingerprintService.fingerprint(appId, sanitized.packageName(), crash)
                : null;
        JankAnalysis jankAnalysis = null;
        if ("jank".equals(sanitized.eventType())) {
            jankAnalysis = jankAnalysisService.analyze(sanitized.jank());
            fingerprint = jankFingerprintService.fingerprint(appId, sanitized.packageName(),
                    sanitized.jank(), jankAnalysis);
        }
        return new StoredEvent(
                appId,
                sanitized.packageName(),
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
                fingerprint == null ? null : ("jank".equals(sanitized.eventType())
                        ? jankFingerprintService.version() : fingerprintService.version()),
                fingerprint == null ? null : "raw_only",
                crash,
                sanitized.jank(),
                jankAnalysis,
                sanitized.frameSceneSummary(),
                sanitized.foregroundSuspensionSummary());
    }
}
