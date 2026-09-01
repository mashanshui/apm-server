package com.shanshui.apmserver.service;

import com.shanshui.apmserver.domain.BatchIngestResponse;
import com.shanshui.apmserver.domain.AuthenticatedApp;
import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.EventError;
import com.shanshui.apmserver.domain.StoredEvent;
import com.shanshui.apmserver.domain.ValidationIssue;
import com.shanshui.apmserver.repository.EventRepository;
import com.shanshui.apmserver.repository.EventStoreUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CrashIngestionService implements EventIngestionService {

    private final CrashEventProcessor processor;
    private final EventRepository repository;
    private final CrashQualityMetrics metrics;
    private final Clock clock = Clock.systemUTC();

    public CrashIngestionService(CrashEventProcessor processor,
                                 EventRepository repository,
                                 CrashQualityMetrics metrics) {
        this.processor = processor;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    public BatchIngestResponse ingest(AuthenticatedApp app, EventBatchRequest request) {
        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw new InvalidBatchException("events 不能为空");
        }
        validateAppPackage(app, request.events());
        java.util.UUID appId = app.appId();
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString() : request.requestId();
        Instant receivedAt = Instant.now(clock);
        List<StoredEvent> validEvents = new ArrayList<>();
        List<EventError> errors = new ArrayList<>();
        int rejectedEvents = 0;
        metrics.received(request.events().size());
        for (int index = 0; index < request.events().size(); index++) {
            EventEnvelope event = request.events().get(index);
            if (event != null && "jank".equals(event.eventType())) {
                metrics.jankReceived();
            }
            try {
                StoredEvent processed = processor.process(appId, event, receivedAt);
                validEvents.add(processed);
                if (isMetricEvent(processed)) {
                    metrics.jankSchemaVersion(processed.schemaVersion());
                    metrics.jankAlgorithmVersion(processed.eventType(), algorithmVersion(processed));
                }
            } catch (EventValidationException ex) {
                rejectedEvents++;
                boolean metricEvent = event != null && isMetricEvent(event);
                if (event != null && "jank".equals(event.eventType())) {
                    metrics.jankRejected();
                }
                if (metricEvent && ex.getIssues().stream()
                        .anyMatch(issue -> "UNSUPPORTED_SCHEMA_VERSION".equals(issue.code()))) {
                    metrics.jankSchemaRejected();
                }
                if (metricEvent && ex.getIssues().stream()
                        .anyMatch(issue -> issue.code().contains("ALGORITHM_VERSION"))) {
                    metrics.jankAlgorithmRejected();
                }
                String eventId = event == null ? null : event.eventId();
                for (ValidationIssue issue : ex.getIssues()) {
                    errors.add(new EventError(index, eventId, issue.code(), issue.message(), false));
                }
            }
        }
        metrics.rejected(errors.size());
        if (validEvents.isEmpty()) {
            return BatchIngestResponse.accepted(requestId, 0, rejectedEvents, 0, errors);
        }
        try {
            var appendResult = repository.append(appId, validEvents);
            metrics.duplicate(appendResult.duplicate());
            if (!validEvents.isEmpty() && validEvents.stream().allMatch(event -> event.isJank())) {
                metrics.jankAccepted(appendResult.accepted());
                metrics.jankDuplicate(appendResult.duplicate());
            }
            for (StoredEvent event : validEvents) {
                Duration delay = Duration.between(event.occurredAt(), receivedAt);
                if (!delay.isNegative()) {
                    metrics.uploadDelay(delay);
                }
                if (event.isCrash()) {
                    if (!delay.isNegative()) {
                        metrics.visibleDelay(delay);
                    }
                } else if (event.isJank()) {
                    metrics.jankVisibleDelay(delay);
                }
            }
            return BatchIngestResponse.accepted(requestId, appendResult.accepted(), rejectedEvents,
                    appendResult.duplicate(), errors);
        } catch (EventStoreUnavailableException ex) {
            metrics.retryableFailure();
            throw ex;
        }
    }

    /** 仅供领域级测试和内部调用；HTTP 接收入口始终使用数据库鉴权得到的应用身份。 */
    public BatchIngestResponse ingest(java.util.UUID appId, EventBatchRequest request) {
        String packageName = request == null || request.events() == null
                ? "" : request.events().stream()
                .filter(event -> event != null && event.packageName() != null)
                .map(EventEnvelope::packageName)
                .findFirst()
                .orElse("");
        return ingest(new AuthenticatedApp(appId, packageName), request);
    }

    private void validateAppPackage(AuthenticatedApp app, List<EventEnvelope> events) {
        for (EventEnvelope event : events) {
            if (event == null || event.packageName() == null
                    || !app.packageName().equals(event.packageName())) {
                throw new PackageNameMismatchException();
            }
        }
    }

    private boolean isMetricEvent(EventEnvelope event) {
        return event != null && ("jank".equals(event.eventType())
                || "frame_scene_summary".equals(event.eventType())
                || "foreground_suspension_summary".equals(event.eventType()));
    }

    private boolean isMetricEvent(StoredEvent event) {
        return event != null && (event.isJank() || event.isFrameSceneSummary()
                || event.isForegroundSuspensionSummary());
    }

    private String algorithmVersion(StoredEvent event) {
        if (event.isJank()) {
            return event.jank() == null ? null : event.jank().algorithmVersion();
        }
        if (event.isFrameSceneSummary()) {
            return event.frameSceneSummary() == null ? null : event.frameSceneSummary().algorithmVersion();
        }
        return event.foregroundSuspensionSummary() == null
                ? null : event.foregroundSuspensionSummary().algorithmVersion();
    }
}
