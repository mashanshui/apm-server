package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.repository.EventStoreUnavailableException;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrashReliabilityTests {

    @Test
    void retryUsesSameEventIdAndCountsDuplicateOnlyOnce() {
        InMemoryEventRepository repository = new InMemoryEventRepository(new com.shanshui.apmserver.config.StorageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope event = CrashTestSupport.event("retry-event", "crash", "retry-session", "retry-device",
                "3.2.0", Instant.now().toEpochMilli(),
                CrashTestSupport.crash("java.lang.IllegalStateException", "boom", 1, "A"));

        assertEquals(1, ingestion.ingest("project-a", CrashTestSupport.batch(List.of(event))).accepted());
        var retry = ingestion.ingest("project-a", CrashTestSupport.batch(List.of(event)));
        assertEquals(0, retry.accepted());
        assertEquals(1, retry.duplicate());
    }

    @Test
    void unavailableStoreIsRetryable() {
        InMemoryEventRepository repository = new InMemoryEventRepository(new com.shanshui.apmserver.config.StorageProperties());
        repository.setAvailable(false);
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope event = CrashTestSupport.event("store-down", "crash", "session", "device", "3.2.0",
                Instant.now().toEpochMilli(), CrashTestSupport.crash("java.lang.RuntimeException", "boom", 1, "A"));

        assertThrows(EventStoreUnavailableException.class,
                () -> ingestion.ingest("project-a", CrashTestSupport.batch(List.of(event))));
    }
}
