package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.JankAnalysis;
import com.shanshui.apmserver.domain.StoredEvent;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.service.CrashSanitizer;
import com.shanshui.apmserver.service.JankAnalysisService;
import com.shanshui.apmserver.service.JankFingerprintService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class JankTestSupport {

    private JankTestSupport() {
    }

    static StoredEvent storedEvent(UUID appId, EventEnvelope source) {
        var properties = CrashTestSupport.ingestProperties();
        EventEnvelope event = new CrashSanitizer(properties).sanitize(source);
        JankAnalysis analysis = new JankAnalysisService(properties).analyze(event.jank());
        JankFingerprintService fingerprints = new JankFingerprintService();
        String fingerprint = fingerprints.fingerprint(appId, event.packageName(), event.jank(), analysis);
        return new StoredEvent(appId, event.packageName(), event.eventId(), "jank",
                Instant.ofEpochMilli(event.occurredAt()), Instant.now(), event.schemaVersion(), event.sessionId(),
                event.anonymousDeviceId(), event.appVersion(), event.versionCode(), event.buildId(),
                event.environment(), event.channel(), event.osVersion(), event.deviceModel(), event.networkType(),
                event.measurements(), event.attributes(), null, null, null, fingerprint, fingerprints.version(),
                "raw_only", null, event.jank(), analysis, null, null);
    }

    static void appendFixture(UUID appId, InMemoryEventRepository repository, EventBatchRequest batch) {
        List<StoredEvent> janks = batch.events().stream()
                .filter(event -> "jank".equals(event.eventType()))
                .map(event -> storedEvent(appId, event))
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
