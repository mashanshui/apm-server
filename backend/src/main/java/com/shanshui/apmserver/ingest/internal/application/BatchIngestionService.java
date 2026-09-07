package com.shanshui.apmserver.ingest.internal.application;

import com.shanshui.apmserver.crash.api.CrashEventProcessing;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.CrashMetrics;
import com.shanshui.apmserver.ingest.api.IngestMetrics;
import com.shanshui.apmserver.jank.api.JankMetricEventProcessing;
import com.shanshui.apmserver.jank.api.JankMetricIngestCommand;
import com.shanshui.apmserver.jank.api.JankMetrics;
import com.shanshui.apmserver.ingest.api.EventIngestionService;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.ingest.api.InvalidBatchException;

import com.shanshui.apmserver.ingest.api.BatchIngestResponse;
import com.shanshui.apmserver.identity.api.AuthenticatedApp;
import com.shanshui.apmserver.identity.api.PackageNameMismatchException;
import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.ingest.api.EventError;
import com.shanshui.apmserver.telemetry.api.SignalIngestResult;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BatchIngestionService implements EventIngestionService {

    private final CrashEventProcessing crashProcessor;
    private final JankMetricEventProcessing jankMetricProcessor;
    private final IngestMetrics ingestMetrics;
    private final CrashMetrics crashMetrics;
    private final JankMetrics jankMetrics;
    private final Clock clock = Clock.systemUTC();

    public BatchIngestionService(CrashEventProcessing crashProcessor,
                                 JankMetricEventProcessing jankMetricProcessor,
                                 IngestMetrics ingestMetrics,
                                 CrashMetrics crashMetrics,
                                 JankMetrics jankMetrics) {
        this.crashProcessor = crashProcessor;
        this.jankMetricProcessor = jankMetricProcessor;
        this.ingestMetrics = ingestMetrics;
        this.crashMetrics = crashMetrics;
        this.jankMetrics = jankMetrics;
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
        List<EventError> errors = new ArrayList<>();
        int rejectedEvents = 0;
        int acceptedEvents = 0;
        int duplicateEvents = 0;
        ingestMetrics.received(request.events().size());
        for (int index = 0; index < request.events().size(); index++) {
            EventEnvelope event = request.events().get(index);
            if (event != null && "jank".equals(event.eventType())) {
                jankMetrics.jankReceived();
            }
            try {
                SignalIngestResult processed = processEvent(appId, event, receivedAt);
                acceptedEvents += processed.appendResult().accepted();
                duplicateEvents += processed.appendResult().duplicate();
                if (isMetricEvent(event)) {
                    jankMetrics.jankSchemaVersion(processed.metadata().schemaVersion());
                    jankMetrics.jankAlgorithmVersion(processed.metadata().eventType(), processed.algorithmVersion());
                }
                Duration delay = Duration.between(processed.metadata().occurredAt(), receivedAt);
                if (!delay.isNegative()) {
                    ingestMetrics.uploadDelay(delay);
                    if ("crash".equals(processed.metadata().eventType())) {
                        crashMetrics.visibleDelay(delay);
                    }
                }
            } catch (EventValidationException ex) {
                rejectedEvents++;
                boolean metricEvent = event != null && isMetricEvent(event);
                if (event != null && "jank".equals(event.eventType())) {
                    jankMetrics.jankRejected();
                }
                if (metricEvent && ex.getIssues().stream()
                        .anyMatch(issue -> "UNSUPPORTED_SCHEMA_VERSION".equals(issue.code()))) {
                    jankMetrics.jankSchemaRejected();
                }
                if (metricEvent && ex.getIssues().stream()
                        .anyMatch(issue -> issue.code().contains("ALGORITHM_VERSION"))) {
                    jankMetrics.jankAlgorithmRejected();
                }
                String eventId = event == null ? null : event.eventId();
                for (ValidationIssue issue : ex.getIssues()) {
                    errors.add(new EventError(index, eventId, issue.code(), issue.message(), false));
                }
            } catch (EventStoreUnavailableException ex) {
                ingestMetrics.retryableFailure();
                throw ex;
            }
        }
        ingestMetrics.rejected(errors.size());
        ingestMetrics.duplicate(duplicateEvents);
        return BatchIngestResponse.accepted(requestId, acceptedEvents, rejectedEvents, duplicateEvents, errors);
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

    private SignalIngestResult processEvent(java.util.UUID appId, EventEnvelope event, Instant receivedAt) {
        if (event != null && "jank".equals(event.eventType())) {
            throw new EventValidationException(List.of(
                    new ValidationIssue("JANK_ARTIFACT_REQUIRED",
                            "卡顿个例必须通过 /ingest/v1/stack-artifacts:parse 上传")));
        }
        if (event != null && ("frame_scene_summary".equals(event.eventType())
                || "foreground_suspension_summary".equals(event.eventType()))) {
            return jankMetricProcessor.ingest(appId, new JankMetricIngestCommand(
                    event.schemaVersion(), event.eventId(), event.eventType(), event.occurredAt(),
                    event.sessionId(), event.anonymousDeviceId(), event.packageName(), event.appVersion(),
                    event.versionCode(), event.buildId(), event.environment(), event.channel(), event.osVersion(),
                    event.deviceModel(), event.networkType(), event.measurements(), event.attributes(),
                    event.frameSceneSummary(), event.foregroundSuspensionSummary()), receivedAt);
        }
        CrashIngestCommand command = event == null ? null : new CrashIngestCommand(
                event.schemaVersion(), event.eventId(), event.eventType(), event.occurredAt(), event.sessionId(),
                event.anonymousDeviceId(), event.packageName(), event.appVersion(), event.versionCode(),
                event.buildId(), event.environment(), event.channel(), event.osVersion(), event.deviceModel(),
                event.networkType(), event.measurements(), event.attributes(), event.crash());
        return crashProcessor.ingest(appId, command, receivedAt);
    }
}
