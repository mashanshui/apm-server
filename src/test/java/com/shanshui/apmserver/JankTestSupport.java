package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;

import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.jank.api.JankAnalysis;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.jank.internal.application.JankSanitizer;
import com.shanshui.apmserver.jank.internal.application.JankAnalysisService;
import com.shanshui.apmserver.jank.internal.application.JankFingerprintService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class JankTestSupport {

    private JankTestSupport() {
    }

    static JankEvent storedEvent(UUID appId, EventEnvelope source) {
        var properties = CrashTestSupport.ingestProperties();
        JankSanitizer sanitizer = new JankSanitizer(properties);
        var payload = sanitizer.sanitizeJank(source.jank());
        JankAnalysis analysis = new JankAnalysisService(properties).analyze(payload);
        JankFingerprintService fingerprints = new JankFingerprintService();
        String packageName = sanitizer.sanitizeIdentifier(source.packageName(), 255);
        String fingerprint = fingerprints.fingerprint(appId, packageName, payload, analysis);
        EventMetadata metadata = new EventMetadata(appId, packageName,
                sanitizer.sanitizeIdentifier(source.eventId(), 128), "jank",
                Instant.ofEpochMilli(source.occurredAt()), Instant.now(), source.schemaVersion(),
                sanitizer.sanitizeIdentifier(source.sessionId(), 128), sanitizer.hashDeviceId(source.anonymousDeviceId()),
                sanitizer.sanitizeText(source.appVersion(), 128), source.versionCode(),
                sanitizer.sanitizeText(source.buildId(), 256), sanitizer.sanitizeIdentifier(source.environment(), 64),
                sanitizer.sanitizeIdentifier(source.channel(), 128), sanitizer.sanitizeIdentifier(source.osVersion(), 64),
                sanitizer.sanitizeText(source.deviceModel(), 256), sanitizer.sanitizeIdentifier(source.networkType(), 32),
                source.measurements(), source.attributes());
        return new JankEvent(metadata, fingerprint, fingerprints.version(), "raw_only", payload, analysis);
    }

    static void appendFixture(UUID appId, InMemoryJankEventRepository repository, EventBatchRequest batch) {
        List<com.shanshui.apmserver.jank.internal.domain.JankStoredSignal> janks = batch.events().stream()
                .filter(event -> "jank".equals(event.eventType()))
                .map(event -> (com.shanshui.apmserver.jank.internal.domain.JankStoredSignal) storedEvent(appId, event))
                .toList();
        repository.append(appId, janks);

        List<EventEnvelope> metrics = batch.events().stream()
                .filter(event -> !"jank".equals(event.eventType()))
                .toList();
        if (!metrics.isEmpty()) {
            var properties = CrashTestSupport.ingestProperties();
            properties.setMaxPastDays(3650);
            properties.setSupportedFpsAlgorithmVersions(List.of("fps-v1", "fps-v2"));
            properties.setSupportedSuspensionAlgorithmVersions(List.of("suspension-v1", "suspension-v2"));
            CrashTestSupport.ingestion(repository, properties)
                    .ingest(appId, new EventBatchRequest(batch.requestId(), metrics));
        }
    }
}
