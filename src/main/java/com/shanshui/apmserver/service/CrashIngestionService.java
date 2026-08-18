package com.shanshui.apmserver.service;

import com.shanshui.apmserver.domain.BatchIngestResponse;
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
public class CrashIngestionService {

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

    public BatchIngestResponse ingest(String projectId, EventBatchRequest request) {
        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw new InvalidBatchException("events 不能为空");
        }
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString() : request.requestId();
        Instant receivedAt = Instant.now(clock);
        List<StoredEvent> validEvents = new ArrayList<>();
        List<EventError> errors = new ArrayList<>();
        int rejectedEvents = 0;
        metrics.received(request.events().size());
        for (int index = 0; index < request.events().size(); index++) {
            EventEnvelope event = request.events().get(index);
            try {
                validEvents.add(processor.process(projectId, event, receivedAt));
            } catch (EventValidationException ex) {
                rejectedEvents++;
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
            var appendResult = repository.append(projectId, validEvents);
            metrics.duplicate(appendResult.duplicate());
            for (StoredEvent event : validEvents) {
                Duration delay = Duration.between(event.occurredAt(), receivedAt);
                if (!delay.isNegative()) {
                    metrics.uploadDelay(delay);
                }
                if (event.isCrash()) {
                    if (!delay.isNegative()) {
                        metrics.visibleDelay(delay);
                    }
                }
            }
            return BatchIngestResponse.accepted(requestId, appendResult.accepted(), rejectedEvents,
                    appendResult.duplicate(), errors);
        } catch (EventStoreUnavailableException ex) {
            metrics.retryableFailure();
            throw ex;
        }
    }
}
